from .. import benchmark
from .. import parser_util
from .. import pd_util
from .. import proto_util
from .. import util
from mininet.net import Mininet
from typing import Callable, Dict, List, NamedTuple
import argparse
import csv
import enum
import mininet
import os
import pandas as pd
import subprocess
import time
import tqdm


class Input(NamedTuple):
    # System-wide parameters.
    net_name: str
    f: int
    num_clients: int
    num_threads_per_client: int
    conflict_rate: int

    # Benchmark parameters.
    duration_seconds: float
    client_lag_seconds: float

    # Client parameters.
    client_repropose_period_seconds: float


class Output(NamedTuple):
    mean_latency_ms: float
    median_latency_ms: float
    p90_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float

    mean_1_second_throughput: float
    median_1_second_throughput: float
    p90_1_second_throughput: float
    p95_1_second_throughput: float
    p99_1_second_throughput: float

    mean_2_second_throughput: float
    median_2_second_throughput: float
    p90_2_second_throughput: float
    p95_2_second_throughput: float
    p99_2_second_throughput: float

    mean_5_second_throughput: float
    median_5_second_throughput: float
    p90_5_second_throughput: float
    p95_5_second_throughput: float
    p99_5_second_throughput: float


class EPaxosNet(object):
    def __init__(self) -> None:
        pass

    def __enter__(self) -> 'EPaxosNet':
        self.net().start()
        return self

    def __exit__(self, cls, exn, traceback) -> None:
        self.net().stop()

    def net(self) -> Mininet:
        raise NotImplementedError()

    def clients(self) -> List[mininet.node.Node]:
        raise NotImplementedError()

    def replicas(self) -> List[mininet.node.Node]:
        raise NotImplementedError()

    def config(self) -> proto_util.Message:
        raise NotImplementedError()


class SingleSwitchNet(EPaxosNet):
    def __init__(self,
                 f: int,
                 num_clients: int) -> None:
        self.f = f
        self._clients: List[mininet.node.Node] = []
        self._replicas: List[mininet.node.Node] = []
        self._net = Mininet()

        switch = self._net.addSwitch('s1')
        self._net.addController('c')

        for i in range(num_clients):
            client = self._net.addHost(f'c{i}')
            self._net.addLink(client, switch)
            self._clients.append(client)

        num_replicas = 2*f + 1
        for i in range(num_replicas):
            replica = self._net.addHost(f'a{i}')
            self._net.addLink(replica, switch)
            self._replicas.append(replica)

    def net(self) -> Mininet:
        return self._net

    def clients(self) -> List[mininet.node.Node]:
        return self._clients

    def replicas(self) -> List[mininet.node.Node]:
        return self._replicas

    def config(self) -> proto_util.Message:
        return {
            'f': self.f,
            'replicaAddress': [
                {'host': a.IP(), 'port': 10000}
                for (i, a) in enumerate(self.replicas())
            ],
        }


def run_benchmark(bench: benchmark.BenchmarkDirectory,
                  args: argparse.Namespace,
                  input: Input,
                  net: EPaxosNet) -> Output:
    # Write config file.
    config_filename = bench.abspath('config.pbtxt')
    bench.write_string(config_filename,
                       proto_util.message_to_pbtext(net.config()))
    bench.log('Config file config.pbtxt written.')

    # Launch replicas.
    replica_procs = []
    for (i, host) in enumerate(net.replicas()):
        proc = bench.popen(
            f=host.popen,
            label=f'replica_{i}',
            cmd = [
                'java',
                '-cp', os.path.abspath(args.jar),
                'frankenpaxos.epaxos.ReplicaMain',
                '--index', str(i),
                '--config', config_filename,
            ],
            profile=args.profile,
        )
        replica_procs.append(proc)
    bench.log('Replicas started.')


    time.sleep(input.client_lag_seconds)
    bench.log('Client lag ended.')

    # Launch clients.
    client_procs = []
    for (i, host) in enumerate(net.clients()):
        proc = bench.popen(
            f=host.popen,
            label=f'client_{i}',
            cmd = [
                'java',
                '-cp', os.path.abspath(args.jar),
                'frankenpaxos.epaxos.BenchmarkClientMain',
                '--host', host.IP(),
                '--port', str(11000),
                '--config', config_filename,
                '--repropose_period',
                    f'{input.client_repropose_period_seconds}s',
                '--duration', f'{input.duration_seconds}s',
                # '--conflict_rate', f'{input.conflict_rate}',
                '--num_threads', str(input.num_threads_per_client),
                '--output_file_prefix', bench.abspath(f'client_{i}'),
            ]
        )
        client_procs.append(proc)
    bench.log('Clients started.')

    # Wait for clients to finish and then terminate leaders and acceptors.
    for proc in client_procs:
        proc.wait()
    for proc in replica_procs:
        proc.terminate()
    bench.log('Clients finished and processes terminated.')

    # Every client thread j on client i writes results to `client_i_j.csv`.
    # We concatenate these results into a single CSV file.
    client_csvs = [bench.abspath(f'client_{i}_{j}.csv')
                   for i in range(input.num_clients)
                   for j in range(input.num_threads_per_client)]
    df = pd_util.read_csvs(client_csvs, parse_dates=['start', 'stop'])
    bench.log('Data read.')
    df = df.set_index('start')
    bench.log('Data index set.')
    df = df.sort_index(0)
    bench.log('Data index sorted.')
    df.to_csv(bench.abspath('data.csv'))
    bench.log('Data written.')

    # Since we concatenate and save the file, we can throw away the originals.
    for client_csv in client_csvs:
        os.remove(client_csv)

    # We also compress the output data since it can get big.
    subprocess.call(['gzip', bench.abspath('data.csv')])
    bench.log('Data compressed.')

    latency_ms = df['latency_nanos'] / 1e6
    throughput_1s = pd_util.throughput(df, 1000)
    throughput_2s = pd_util.throughput(df, 2000)
    throughput_5s = pd_util.throughput(df, 5000)
    return Output(
        mean_latency_ms = latency_ms.mean(),
        median_latency_ms = latency_ms.median(),
        p90_latency_ms = latency_ms.quantile(.90),
        p95_latency_ms = latency_ms.quantile(.95),
        p99_latency_ms = latency_ms.quantile(.99),

        mean_1_second_throughput = throughput_1s.mean(),
        median_1_second_throughput = throughput_1s.median(),
        p90_1_second_throughput = throughput_1s.quantile(.90),
        p95_1_second_throughput = throughput_1s.quantile(.95),
        p99_1_second_throughput = throughput_1s.quantile(.99),

        mean_2_second_throughput = throughput_2s.mean(),
        median_2_second_throughput = throughput_2s.median(),
        p90_2_second_throughput = throughput_2s.quantile(.90),
        p95_2_second_throughput = throughput_2s.quantile(.95),
        p99_2_second_throughput = throughput_2s.quantile(.99),

        mean_5_second_throughput = throughput_5s.mean(),
        median_5_second_throughput = throughput_5s.median(),
        p90_5_second_throughput = throughput_5s.quantile(.90),
        p95_5_second_throughput = throughput_5s.quantile(.95),
        p99_5_second_throughput = throughput_5s.quantile(.99),
    )


def run_suite(args: argparse.Namespace,
              inputs: List[Input],
              make_net: Callable[[Input], EPaxosNet]) -> None:
    assert len(inputs) > 0, inputs

    with benchmark.SuiteDirectory(args.suite_directory, 'epaxos') as suite:
        print(f'Running benchmark suite in {suite.path}.')
        suite.write_dict('args.json', vars(args))
        suite.write_string('inputs.txt', '\n'.join(str(i) for i in inputs))

        results_file = suite.create_file('results.csv')
        results_writer = csv.writer(results_file)
        results_writer.writerow(Input._fields + Output._fields)
        results_file.flush()

        for input in tqdm.tqdm(inputs):
            with suite.benchmark_directory() as bench:
                with make_net(input) as net:
                    bench.write_string('input.txt', str(input))
                    bench.write_dict('input.json', input._asdict())
                    output = run_benchmark(bench, args, input, net)
                    row = util.flatten_tuple(input) + list(output)
                    results_writer.writerow([str(x) for x in row])
                    results_file.flush()


def _main(args) -> None:
    inputs = [
        Input(
            net_name='SingleSwitchNet',
            f=1,
            num_clients=num_clients,
            num_threads_per_client=1,
            duration_seconds=15,
            conflict_rate=0,
            client_lag_seconds=3,
            client_repropose_period_seconds=10,
        )
        for num_clients in [1, 2]
    ] * 2

    def make_net(input) -> EPaxosNet:
        return SingleSwitchNet(f=input.f, num_clients=input.num_clients)

    run_suite(args, inputs, make_net)


def get_parser() -> argparse.ArgumentParser:
    return parser_util.get_benchmark_parser()


if __name__ == '__main__':
    _main(get_parser().parse_args())