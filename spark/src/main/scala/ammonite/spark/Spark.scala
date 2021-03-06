package ammonite.spark

import java.io.IOException
import java.net._
import java.io.File
import java.nio.file.Files
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }

import ammonite.api.{ Classpath, Interpreter }
import ammonite.api.ModuleConstructor._
import ammonite.spark.Compat.sparkVersion

import org.apache.spark.scheduler._
import org.apache.spark.{ SparkConf, SparkContext, SparkContextOps }
import org.apache.spark.sql.SQLContext

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

import scala.annotation.meta.field
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

/** The spark entry point from an Ammonite session */
class Spark(ttl: Duration = Spark.defaultTtl)(implicit
  @(transient @field) interpreter: Interpreter,
  @(transient @field) classpath: Classpath
) extends Serializable {

  private lazy val host =
    sys.env.getOrElse("HOST", InetAddress.getLocalHost.getHostAddress)

  private var _classServerURI: URI = null
  @transient private var _classServer: Server = null

  private def classServerURI = {
    if (_classServerURI == null)
      initClassServer()
    _classServerURI
  }

  private def initClassServer() = {
    val socketAddress = InetSocketAddress.createUnresolved(host, {
      val s = new ServerSocket(0)
      val port = s.getLocalPort
      s.close()
      port
    })

    val handler = new AbstractHandler {
      def handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse): Unit = {
        val path = target.stripPrefix("/").split('/').toList.filter(_.nonEmpty)

        def fromClassMaps =
          for {
            List(item) <- Some(path)
            b <- classpath.fromAddedClasses("compile", item.stripSuffix(".class"))
          } yield b

        def fromDirs =
          classpath.path()
            .filterNot(f => f.isFile && f.getName.endsWith(".jar"))
            .map(path.foldLeft(_)(new File(_, _)))
            .collectFirst{ case f if f.exists() => Files.readAllBytes(f.toPath) }

        fromClassMaps orElse fromDirs match {
          case Some(bytes) =>
            response setContentType "application/octet-stream"
            response setStatus HttpServletResponse.SC_OK
            baseRequest setHandled true
            response.getOutputStream write bytes

          case None =>
            response.setContentType("text/plain")
            response.setStatus(HttpServletResponse.SC_NOT_FOUND)
            baseRequest.setHandled(true)
            response.getWriter.println("not found")
        }
      }
    }

    val server = new Server(socketAddress)
    server.setHandler(handler)
    server.start()

    _classServerURI = new URI(s"http://$socketAddress")
    _classServer = server
  }

  private def defaultMaster = {
    val envMaster = sys.env.get("MASTER")
    val propMaster = sys.props.get("spark.master")
    propMaster.orElse(envMaster).getOrElse("local[*]")
  }

  private def availablePort(from: Int): Int = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(from)
      from
    }
    catch {
      case _: IOException =>
        availablePort(from + 1)
    }
    finally {
      if (socket != null) socket.close()
    }
  }

  /** Filtered out jars (we assume the spark master/slaves already have them) */
  private lazy val sparkJars = classpath.resolve(
    "org.apache.spark" %% "spark-core" % sparkVersion,
    "org.apache.spark" %% "spark-sql" % sparkVersion
  ).toSet

  /** Called before creation of the `SparkContext` to setup the `SparkConf`. */
  def setConfDefaults(conf: SparkConf): Unit = {
    implicit class SparkConfExtensions(val conf: SparkConf) {
      def setIfMissingLazy(key: String, value: => String): conf.type = {
        if (conf.getOption(key).isEmpty)
          conf.set(key, value)
        conf
      }
    }

    conf
      .setIfMissing("spark.master", defaultMaster)
      .setIfMissing("spark.app.name", "Ammonite Shell")
      .setIfMissingLazy(
        "spark.jars",
        classpath
          .path()
          .filter(f => f.isFile && f.getName.endsWith(".jar"))
          .filterNot(sparkJars)
          .map(_.toURI.toString)
          .mkString(",")
      )
      .setIfMissingLazy("spark.repl.class.uri", classServerURI.toString)
      .setIfMissingLazy("spark.ui.port", availablePort(4040).toString)

    if (conf.getOption("spark.executor.uri").isEmpty)
      for (execUri <- Option(System.getenv("SPARK_EXECUTOR_URI")))
        conf.set("spark.executor.uri", execUri)

    if (conf.getOption("spark.home").isEmpty)
      for (sparkHome <- Option(System.getenv("SPARK_HOME")))
        conf.set("spark.home", sparkHome)
  }

  @transient private var _sparkConf: SparkConf = null
  @transient private var _sc: SparkContext = null
  @transient private var _updateTtl: () => Unit = () => ()
  @transient private var _cancelTtl: () => Unit = () => ()

  /** The `SparkConf` associated to this handle */
  def sparkConf: SparkConf = {
    if (_sparkConf == null)
      _sparkConf = new SparkConf()

    _sparkConf
  }

  /** Helper function to add custom settings to the `SparkConf` associated to this handle */
  def withConf(f: SparkConf => SparkConf): Unit =
    _sparkConf = f(sparkConf)

  classpath.onPathsAdded("compile") { newJars =>
    if (_sc != null)
      newJars.filterNot(sparkJars).foreach(_sc addJar _.toURI.toString)
  }

  interpreter.onStop {
    stop()
  }

  /**
   * The `SparkContext` associated to this handle.
   *
   * Lazily initialized on first call.
   *
   * Its config can be customized prior to its initialization through `sparkConf`
   * or with the `withConf` method.
   *
   * Its launch triggers the launch of a web server that serves the REPL build
   * products.
   *
   * Gets automatically stopped upon host interpreter stopping. Can also be stopped
   * manually with the `stop` method.
   *
   * If stopped through the `stop` method, calling `sc` again will trigger the creation
   * of a new SparkContext.
   */
  def sc: SparkContext = {
    if (_sc == null) {
      setConfDefaults(sparkConf)
      val master = sparkConf.get("spark.master")
      if ((!master.startsWith("local") || master.contains("cluster")) && sparkConf.getOption("spark.home").isEmpty)
        throw new IllegalArgumentException(s"Spark master set to $master and spark.home not set")

      _sc = new Spark.SparkContext(sparkConf)

      ttl match {
        case d: FiniteDuration =>
          val t = Spark.addTtl(_sc, d)
          _updateTtl = t._1
          _cancelTtl = t._2
        case _ =>
      }
    }

    _updateTtl()
    _sc
  }

  def cancelTtl(): Unit = {
    _cancelTtl()
  }

  /** Alias for `sc` */
  def sparkContext = sc

  /** Triggers the initialization of the SparkContext, if not already started. */
  def start(): Unit = {
    sc
  }

  /**
   * Stops the `SparkContext` associated to this handle. The context previously
   * returned should not be considered valid after that. The web server launched
   * along with the context will be stopped too.
   *
   * Calling `sc` again will trigger the creation of a new `SparkContext`
   */
  def stop() = {
    if (_sc != null) {
      _sc.stop()
      _sc = null
    }
    if (_classServer != null) {
      _classServer.stop()
      _classServer = null
    }
    if (_classServerURI != null)
      _classServerURI = null
  }

  /** SparkSQL context associated to this handle. Lazily initialized on first call. */
  lazy val sqlContext: SQLContext = new SQLContext(sc)

  override def toString: String =
    "Spark" + (if (_sc == null) "(uninitialized)" else "")
}

object Spark {
  class SparkContext(sparkConf: SparkConf)
    extends org.apache.spark.SparkContext(sparkConf) {
    override def toString = "SparkContext"
  }

  private implicit def toSparkContextOps(sc: org.apache.spark.SparkContext) =
    new SparkContextOps(sc)

  def addTtl(sc: org.apache.spark.SparkContext, ttl: FiniteDuration): (() => Unit, () => Unit) = {

    @volatile var lastAccess = System.currentTimeMillis()

    def accessed(): Unit = {
      lastAccess = System.currentTimeMillis()
    }

    def shouldBeStopped(): Boolean = {
      val currentTs = System.currentTimeMillis()
      currentTs > lastAccess + ttl.toMillis
    }

    val listener = new SparkListener {
      override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) =
        accessed()
      override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted) =
        accessed()
      override def onTaskStart(taskStart: SparkListenerTaskStart) =
        accessed()
      override def onTaskGettingResult(taskGettingResult: SparkListenerTaskGettingResult) =
        accessed()
      override def onTaskEnd(taskEnd: SparkListenerTaskEnd) =
        accessed()
      override def onJobStart(jobStart: SparkListenerJobStart) =
        accessed()
      override def onJobEnd(jobEnd: SparkListenerJobEnd) =
        accessed()
      override def onApplicationStart(applicationStart: SparkListenerApplicationStart) =
        accessed()
    }

    sc.addSparkListener(listener)

    @volatile var cancelled = false

    val t = new Thread {
      override def run() =
        while (!sc.isStopped && !cancelled)
          if (shouldBeStopped())
            sc.stop()
          else
            Thread.sleep(2000L)
    }

    t.start()

    (() => accessed(), () => { cancelled = true })
  }

  def defaultTtl: Duration = {

    val fromEnv = sys.env.get("SPARK_CONTEXT_TTL")
      .flatMap(s => Try(Duration(s)).toOption)
    def fromProps = sys.props.get("sparkContext.ttl")
      .flatMap(s => Try(Duration(s)).toOption)

    fromEnv.orElse(fromProps).getOrElse(Duration.Inf)
  }
}