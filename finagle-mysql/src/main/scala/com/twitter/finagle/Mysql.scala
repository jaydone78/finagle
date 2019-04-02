package com.twitter.finagle

import com.twitter.finagle.client.{DefaultPool, StackClient, StdStackClient, Transporter}
import com.twitter.finagle.mysql._
import com.twitter.finagle.mysql.param._
import com.twitter.finagle.mysql.transport.Packet
import com.twitter.finagle.param.{
  ExceptionStatsHandler => _,
  Monitor => _,
  ResponseClassifier => _,
  Tracer => _,
  _
}
import com.twitter.finagle.service.{ResponseClassifier, RetryBudget}
import com.twitter.finagle.stats.{ExceptionStatsHandler, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing._
import com.twitter.finagle.transport.{Transport, TransportContext}
import com.twitter.io.Buf
import com.twitter.util.{Duration, Future, Monitor}
import java.net.SocketAddress

/**
 * Supplements a [[com.twitter.finagle.Client]] with convenient
 * builder methods for constructing a mysql client.
 */
trait MysqlRichClient { self: com.twitter.finagle.Client[Request, Result] =>

  /**
   * Whether the client supports unsigned integer fields
   */
  protected val supportUnsigned: Boolean

  def richClientStatsReceiver: StatsReceiver = NullStatsReceiver

  /**
   * Creates a new `RichClient` connected to the logical
   * destination described by `dest` with the assigned
   * `label`. The `label` is used to scope client stats.
   */
  def newRichClient(dest: Name, label: String): mysql.Client with mysql.Transactions =
    mysql.Client(newClient(dest, label), richClientStatsReceiver, supportUnsigned)

  /**
   * Creates a new `RichClient` connected to the logical
   * destination described by `dest`.
   *
   * @param dest the location to connect to, e.g. "host:port". See the
   *             [[https://twitter.github.io/finagle/guide/Names.html user guide]]
   *             for details on destination names.
   */
  def newRichClient(dest: String): mysql.Client with mysql.Transactions =
    mysql.Client(newClient(dest), richClientStatsReceiver, supportUnsigned)
}

object MySqlClientTracingFilter {
  object Stackable extends Stack.Module1[Label, ServiceFactory[Request, Result]] {
    val role: Stack.Role = ClientTracingFilter.role
    val description: String = "Add MySql client specific annotations to the trace"
    def make(
      _label: Label,
      next: ServiceFactory[Request, Result]
    ): ServiceFactory[Request, Result] = {
      // TODO(jeff): should be able to get this directly from ClientTracingFilter
      val annotations = new AnnotatingTracingFilter[Request, Result](
        _label.label,
        Annotation.ClientSend,
        Annotation.ClientRecv
      )
      annotations.andThen(TracingFilter).andThen(next)
    }
  }

  object TracingFilter extends SimpleFilter[Request, Result] {
    def apply(request: Request, service: Service[Request, Result]): Future[Result] = {
      val trace = Trace()
      if (trace.isActivelyTracing) {
        request match {
          case QueryRequest(sqlStatement) => trace.recordBinary("mysql.query", sqlStatement)
          case PrepareRequest(sqlStatement) => trace.recordBinary("mysql.prepare", sqlStatement)
          // TODO: save the prepared statement and put it in the executed request trace
          case ExecuteRequest(id, _, _, _) => trace.recordBinary("mysql.execute", id)
          case _ => trace.record("mysql." + request.getClass.getSimpleName.replace("$", ""))
        }
      }

      service(request)
    }
  }
}

/**
 * @example {{{
 * val client = Mysql.client
 *   .withCredentials("<username>", "<password>")
 *   .withDatabase("<db>")
 *   .newRichClient("inet!localhost:3306")
 * }}}
 */
object Mysql extends com.twitter.finagle.Client[Request, Result] with MysqlRichClient {

  protected val supportUnsigned: Boolean = UnsignedColumns.param.default.supported

  object Client {

    private val params: Stack.Params = StackClient.defaultParams +
      ProtocolLibrary("mysql") +
      DefaultPool.Param(
        low = 0,
        high = 1,
        bufferSize = 0,
        idleTime = Duration.Top,
        maxWaiters = Int.MaxValue
      )

    private val stack: Stack[ServiceFactory[Request, Result]] = StackClient.newStack
      .replace(ClientTracingFilter.role, MySqlClientTracingFilter.Stackable)
      // Note: there is a stack overflow in insertAfter using CanStackFrom, thus the module.
      .insertAfter(DefaultPool.Role, PoisonConnection.module)
      .prepend(RollbackFactory.module)
  }

  /**
   * Implements a mysql client in terms of a
   * [[com.twitter.finagle.client.StackClient]]. The client inherits a wealth
   * of features from finagle including connection pooling and load
   * balancing.
   *
   * Additionally, this class provides methods via [[MysqlRichClient]] for constructing
   * a client which exposes an API that has use case specific methods, for example
   * [[mysql.Client.read]], [[mysql.Client.modify]], and [[mysql.Client.prepare]].
   * This is an easier experience for most users.
   *
   * @example
   * {{{
   * import com.twitter.finagle.Mysql
   * import com.twitter.finagle.mysql.Client
   * import com.twitter.util.Future
   *
   * val client: Client = Mysql.client
   *   .withCredentials("username", "password")
   *   .withDatabase("database")
   *   .newRichClient("host:port")
   * val names: Future[Seq[String]] =
   *   client.select("SELECT name FROM employee") { row =>
   *     row.stringOrNull("name")
   *   }
   * }}}
   */
  case class Client(
    stack: Stack[ServiceFactory[Request, Result]] = Client.stack,
    params: Stack.Params = Client.params)
      extends StdStackClient[Request, Result, Client]
      with WithSessionPool[Client]
      with WithDefaultLoadBalancer[Client]
      with MysqlRichClient {

    protected val supportUnsigned: Boolean = params[UnsignedColumns].supported

    protected def copy1(
      stack: Stack[ServiceFactory[Request, Result]] = this.stack,
      params: Stack.Params = this.params
    ): Client = copy(stack, params)

    protected type In = Buf
    protected type Out = Buf
    protected type Context = TransportContext

    protected def newTransporter(addr: SocketAddress): Transporter[In, Out, Context] =
      new MysqlTransporter(addr, params)

    protected def newDispatcher(
      transport: Transport[Buf, Buf] {
        type Context <: Client.this.Context
      }
    ): Service[Request, Result] =
      mysql.ClientDispatcher(transport.map(_.toBuf, Packet.fromBuf), params)

    /**
     * The maximum number of concurrent prepare statements.
     */
    def withMaxConcurrentPrepareStatements(num: Int): Client =
      configured(MaxConcurrentPrepareStatements(num))

    /**
     * The credentials to use when authenticating a new session.
     *
     * @param p if `null`, no password is used.
     */
    def withCredentials(u: String, p: String): Client =
      configured(Credentials(Option(u), Option(p)))

    /**
     * Database to use when this client establishes a new session.
     */
    def withDatabase(db: String): Client =
      configured(Database(Option(db)))

    /**
     * The default character set used when establishing a new session.
     */
    def withCharset(charset: Short): Client =
      configured(Charset(charset))

    /**
     * Don't set the CLIENT_FOUND_ROWS flag when establishing a new
     * session. This will make "INSERT ... ON DUPLICATE KEY UPDATE"
     * statements return the "correct" update count.
     *
     * See https://dev.mysql.com/doc/refman/5.7/en/information-functions.html#function_row-count
     */
    def withAffectedRows(): Client =
      configured(FoundRows(false))

    /**
     * Installs a module on the client which issues a ROLLBACK statement when a service is put
     * back into the pool. This exists to ensure that a "clean" connection is always returned
     * from the connection pool. For example, it prevents situations where an unfinished
     * transaction has been written to the wire, the service has been released back into
     * the pool, the same service is again checked out of the pool, and a statement
     * that causes an implicit commit is issued.
     *
     * The additional work incurred for the rollback may result in less throughput from the
     * connection pool and, as such, may require configuring the pool (via `withSessionPool`)
     * to offer more available connections connections.
     *
     * @see [[com.twitter.finagle.mysql.RollbackFactory]]
     * @see https://dev.mysql.com/doc/en/implicit-commit.html
     * @note this module is installed by default.
     */
    def withRollback: Client =
      if (stack.contains(RollbackFactory.Role)) {
        this
      } else {
        withStack(stack.prepend(RollbackFactory.module))
      }

    /**
     * Removes the module on the client which issues a ROLLBACK statement each time a
     * service is put back into the pool. This ''may'' result in better performance
     * at the risk of receiving a connection from the pool with uncommitted state.
     *
     * Instead of disabling this feature, consider configuring the connection pool
     * for the client (via `withSessionPool`) to offer more available connections.
     *
     * @see [[com.twitter.finagle.mysql.RollbackFactory]]
     * @see https://dev.mysql.com/doc/en/implicit-commit.html
     * @note the rollback module is installed by default.
     */
    def withNoRollback: Client =
      withStack(stack.remove(RollbackFactory.Role))

    // Java-friendly forwarders
    // See https://issues.scala-lang.org/browse/SI-8905
    override val withSessionPool: SessionPoolingParams[Client] =
      new SessionPoolingParams(this)
    override val withLoadBalancer: DefaultLoadBalancingParams[Client] =
      new DefaultLoadBalancingParams(this)
    override val withTransport: ClientTransportParams[Client] =
      new ClientTransportParams(this)
    override val withSession: ClientSessionParams[Client] =
      new ClientSessionParams(this)
    override val withSessionQualifier: SessionQualificationParams[Client] =
      new SessionQualificationParams(this)
    override val withAdmissionControl: ClientAdmissionControlParams[Client] =
      new ClientAdmissionControlParams(this)

    override def withLabel(label: String): Client = super.withLabel(label)
    override def withStatsReceiver(statsReceiver: StatsReceiver): Client =
      super.withStatsReceiver(statsReceiver)
    override def withMonitor(monitor: Monitor): Client = super.withMonitor(monitor)
    override def withTracer(tracer: Tracer): Client = super.withTracer(tracer)
    override def withExceptionStatsHandler(exceptionStatsHandler: ExceptionStatsHandler): Client =
      super.withExceptionStatsHandler(exceptionStatsHandler)
    override def withRequestTimeout(timeout: Duration): Client =
      super.withRequestTimeout(timeout)
    override def withResponseClassifier(responseClassifier: ResponseClassifier): Client =
      super.withResponseClassifier(responseClassifier)
    override def withRetryBudget(budget: RetryBudget): Client = super.withRetryBudget(budget)
    override def withRetryBackoff(backoff: Stream[Duration]): Client =
      super.withRetryBackoff(backoff)

    override def withStack(stack: Stack[ServiceFactory[Request, Result]]): Client =
      super.withStack(stack)
    override def withStack(
      fn: Stack[ServiceFactory[Request, Result]] => Stack[ServiceFactory[Request, Result]]
    ): Client =
      super.withStack(fn)
    override def configured[P](psp: (P, Stack.Param[P])): Client = super.configured(psp)
    override def filtered(filter: Filter[Request, Result, Request, Result]): Client =
      super.filtered(filter)

    override def richClientStatsReceiver: StatsReceiver = params[Stats].statsReceiver
  }

  def client: Mysql.Client = Client()

  def newClient(dest: Name, label: String): ServiceFactory[Request, Result] =
    client.newClient(dest, label)

  def newService(dest: Name, label: String): Service[Request, Result] =
    client.newService(dest, label)
}
