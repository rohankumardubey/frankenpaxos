package frankenpaxos

class FileLogger(filename: String) extends Logger {
  private val file = new java.io.File(filename)
  private val writer = new java.io.PrintWriter(file)

  private def withThreadId(s: String): String = {
    s"[Thread ${Thread.currentThread().getId()}] " + s
  }

  override def fatal(message: String): Nothing = {
    writer.println(withThreadId("[FATAL] ") + message)
    val stackTraceElements =
      for (e <- Thread.currentThread().getStackTrace())
        yield e.toString()
    writer.println(stackTraceElements.mkString("\n"))
    writer.flush()
    System.exit(1)
    ???
  }

  override def error(message: String): Unit = {
    writer.println(withThreadId("[ERROR] ") + message)
    writer.flush()
  }

  override def warn(message: String): Unit = {
    writer.println(withThreadId("[WARN] ") + message)
    writer.flush()
  }

  override def info(message: String): Unit = {
    writer.println(withThreadId("[INFO] ") + message)
    writer.flush()
  }

  override def debug(message: String): Unit = {
    writer.println(withThreadId("[DEBUG] ") + message)
    writer.flush()
  }
}