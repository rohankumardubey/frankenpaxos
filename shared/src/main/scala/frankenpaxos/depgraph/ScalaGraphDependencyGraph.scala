package frankenpaxos.depgraph

import scala.collection.mutable
import scala.scalajs.js.annotation.JSExportAll
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.GraphPredef._
import scalax.collection.mutable.Graph

// ScalaGraphDependencyGraph is a DependencyGraph implemented using scala-graph
// [1]. scala-graph is not a very complete library. It is missing a lot of
// useful methods. For example, you can traverse weakly connected components in
// topological order but not strongly connected components! This makes it hard
// to implement DependencyGraph, but because scala-graph is a scala library, we
// can use it in the JS visualizations.
//
// [1]: http://www.scala-graph.org/
@JSExportAll
class ScalaGraphDependencyGraph[Key, SequenceNumber]()(
    implicit override val keyOrdering: Ordering[Key],
    implicit override val sequenceNumberOrdering: Ordering[SequenceNumber]
) extends DependencyGraph[Key, SequenceNumber] {
  // We implement ScalaGraphDependencyGraph the same way we implement
  // JgraphtDependencyGraph. See JgraphtDependencyGraph for documentation.
  private val graph = Graph[Key, DiEdge]()
  private val committed = mutable.Set[Key]()
  private val sequenceNumbers = mutable.Map[Key, SequenceNumber]()
  private val executed = mutable.Set[Key]()

  override def toString(): String = graph.toString

  override def commit(
      key: Key,
      sequenceNumber: SequenceNumber,
      dependencies: Set[Key]
  ): Seq[Key] = {
    // Ignore commands that have already been committed.
    if (committed.contains(key) || executed.contains(key)) {
      return Seq()
    }

    // Update our bookkeeping.
    committed += key
    sequenceNumbers(key) = sequenceNumber

    // Update the graph.
    graph.add(key)
    for (dependency <- dependencies) {
      // If a dependency has already been executed, we don't add an edge to it.
      if (!executed.contains(dependency)) {
        graph.add(dependency)
        graph.add(key ~> dependency)
      }
    }

    // Execute the graph.
    execute()
  }

  private def isEligible(key: Key): Boolean = {
    committed.contains(key) &&
    graph.outerNodeTraverser(graph.get(key)).forall(committed.contains(_))
  }

  private def execute(): Seq[Key] = {
    // Filter out all vertices that are not eligible.
    val eligibleGraph = graph.filter(isEligible)

    // Condense the graph.
    val components = eligibleGraph.strongComponentTraverser()
    val componentIndex: Map[Key, eligibleGraph.Component] = {
      for {
        component <- components
        node <- component.nodes
      } yield {
        node.toOuter -> component
      }
    }.toMap
    val condensation = Graph[eligibleGraph.Component, DiEdge]()
    components.foreach(condensation.add(_))
    for {
      component <- components
      node <- component.nodes
      successor <- node.diSuccessors
    } {
      condensation.add(componentIndex(node) ~> componentIndex(successor))
    }

    // Reverse the edges of the graph.
    val edges = condensation.edges.toSeq
    for (edge <- edges) {
      val src = edge.head.toOuter
      val dst = edge.tail.head.toOuter
      condensation.remove(edge.toOuter)
      condensation.add(dst ~> src)
    }

    // Iterate through the graph in topological order. topologicalSort returns
    // either a node that is part of a cycle (Left) or the topological order.
    // Condensations are acyclic, so we should always get Right.
    val executable: Seq[Key] = condensation.topologicalSort match {
      case Left(node) =>
        throw new IllegalStateException(
          s"Condensation $condensation has a cycle."
        )
      case Right(topologicalOrder) =>
        topologicalOrder
          .flatMap(component => {
            component.nodes
              .map(_.toOuter)
              .toSeq
              .sortBy(key => (sequenceNumbers(key), key))
          })
          .toSeq
    }

    for (key <- executable) {
      graph.remove(key)
      committed -= key
      sequenceNumbers -= key
      executed += key
    }

    executable
  }

// Returns the current set of nodes. This method is really only useful for
// the Javascript visualizations.
  def nodes: Set[Key] =
    graph.nodes.map(_.toOuter).toSet

// Returns the current set of edges. This method is really only useful for
// the Javascript visualizations.
  def edges: Set[(Key, Key)] =
    graph.edges.map(edge => (edge.head.toOuter, edge.tail.head.toOuter)).toSet
}