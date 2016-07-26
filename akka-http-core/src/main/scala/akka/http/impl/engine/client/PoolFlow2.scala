package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolFlow2.{ RequestContext, ResponseContext }
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream._
import akka.stream.impl.FixedSizeBuffer
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.stream.stage._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }

object PoolFlow2 {

  case class RequestContext(request: HttpRequest, responsePromise: Promise[HttpResponse], retriesLeft: Int) {
    require(retriesLeft >= 0)
  }
  case class ResponseContext(rc: RequestContext, response: Try[HttpResponse])

  def apply(connectionFlow: Flow[HttpRequest, HttpResponse, Any], settings: ConnectionPoolSettings, log: LoggingAdapter) =
    Flow.fromGraph(new PoolFlow2(connectionFlow, settings, log))
}

class PoolFlow2(connectionFlow: Flow[HttpRequest, HttpResponse, Any], settings: ConnectionPoolSettings, log: LoggingAdapter)
  extends GraphStage[FlowShape[RequestContext, ResponseContext]] {

  import settings._

  private val in = Inlet[RequestContext]("requestContext")
  private val out = Outlet[ResponseContext]("responseContext")

  override val shape = FlowShape(in, out)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {

    val pulled = FixedSizeBuffer[Connection](maxConnections)

    var responses = FixedSizeBuffer[(Connection, ResponseContext)](maxConnections)
    var retries = FixedSizeBuffer[(Connection, RequestContext)](maxConnections)

    var counter = 0
    private val openConnections = new ArrayBuffer[Connection](maxConnections)

    def connect() = {
      log.warning(s"open $counter")
      openConnections += new Connection(counter)
      counter += 1
    }

    def _pull[T](in: Inlet[T]): Unit = {
      log.warning("PULL")
      pull(in)
    }

    def _pullN[T](in: Inlet[T], n: Int): Unit = {
      log.warning(s"PULL from $n")
      pull(in)
    }

    def _push[T](out: Outlet[T], elem: T): Unit = {
      log.warning(s"PUSH $elem")
      push(out, elem)
    }

    def _pushN[T](out: Outlet[T], elem: T, n: Int): Unit = {
      log.warning(s"PUSH $elem from $n")
      push(out, elem)
    }

    final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
      def this(msg: String) = this(msg, null)
    }

    object Connection {

      // the Connection keeps the state as instances of this ADT
      sealed trait State {
        def saturated: Boolean = false
      }

      // the connection is connected with no requests currently in flight
      case object Idle extends State

      // the connection has a number of requests in flight and all of them are idempotent
      // which allows more requests to be pipelined onto the connection if required
      final case class Loaded(openIdempotentRequests: Int) extends State {
        require(openIdempotentRequests > 0)
        override val saturated = openIdempotentRequests == pipeliningLimit
      }

      // the connection has a number of requests in flight and the last one of these is not
      // idempotent which blocks the connection for more pipelined requests
      case class Busy(openRequests: Int) extends State {
        require(openRequests > 0)
        override val saturated = true
      }
    }

    private class Connection(val idx: Int) extends InHandler with OutHandler { self ⇒
      import Connection._

      def _pullFromCon(): Unit = {
        log.warning(s"$idx.PULL()")
        fromCon.pull()
      }

      def _pushToCon(rc: RequestContext): Unit = {
        log.warning(s"$idx.PUSH($rc)")
        toCon.push(rc)
      }

      val toCon = new SubSourceOutlet[RequestContext]("toConnection")
      val fromCon = new SubSinkInlet[(RequestContext, Try[HttpResponse])]("fromConnection")

      var state: State = Idle

      def stateAfterRequestDispatch(method: HttpMethod): State =
        state match {
          case Idle      ⇒ if (method.isIdempotent) Loaded(1) else Busy(1)
          case Loaded(n) ⇒ if (method.isIdempotent) Loaded(n + 1) else Busy(n + 1)
          case Busy(_)   ⇒ throw new IllegalStateException("Request scheduled onto busy connection?")
        }

      def statetAfterResultHandling: State =
        state match {
          case Loaded(1) ⇒ Idle
          case Loaded(n) ⇒ Loaded(n - 1)
          case Busy(1)   ⇒ Idle
          case Busy(n)   ⇒ Busy(n - 1)
          case _         ⇒ throw new IllegalStateException(s"RequestCompleted on $state connection?")
        }

      def dispatchRequest(requestContext: RequestContext) = {
        _pushToCon(requestContext)
        state = stateAfterRequestDispatch(requestContext.request.method)
      }

      def deliverResult(reqCtx: RequestContext, result: Try[HttpResponse]): Unit = {
        val responseCtx = ResponseContext(reqCtx, result)
        if (responses.isEmpty && isAvailable(out)) {
          _pushN(out, responseCtx, idx)
          _pullFromCon()
        } else {
          responses.enqueue((this, responseCtx))
        }
        state = statetAfterResultHandling
      }

      def handleFailure(reqCtx: RequestContext, result: Try[HttpResponse]) =
        if (result.isSuccess)
          log.warning("unexpected response")
        else if (reqCtx.retriesLeft == 0)
          deliverResult(reqCtx, result)
        else {
          val retryCtx = reqCtx.copy(retriesLeft = reqCtx.retriesLeft - 1)
          if (retries.isEmpty && pulled.nonEmpty)
            pulled.dequeue()._pushToCon(retryCtx)
          else
            retries.enqueue((self, retryCtx))
          self._pullFromCon()
        }

      // pull from connection
      override def onPull(): Unit = {
        log.warning(s"onPullFromCon $idx")
        if (!state.saturated)
          if (retries.nonEmpty) {
            val (retryCon, retryCtx) = retries.dequeue()
            dispatchRequest(retryCtx)
            retryCon._pullFromCon()
          } else if (isAvailable(in)) {
            dispatchRequest(grab(in))
            _pullN(in, idx)
          } else
            pulled.enqueue(this)
      }

      // push from connection
      override def onPush(): Unit = {
        val wasSaturated = state.saturated

        val (reqCtx, result) = fromCon.grab()
        log.warning(s"onPush($reqCtx) from $idx")
        result match {
          case Success(response) ⇒
            deliverResult(reqCtx, result)
            if (HttpMessage.connectionCloseExpected(response.protocol, response.header[headers.Connection]))
              setDisconnectedHnadler()
          case _ ⇒
            handleFailure(reqCtx, result)
            setDisconnectedHnadler()
        }

        if (wasSaturated && toCon.isAvailable) onPull()
      }

      override def onUpstreamFinish(): Unit = {
        log.warning(s"onUpstreamFinish $idx")
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        log.warning(s"onUpstreamFailure($ex) $idx")
      }

      override def onDownstreamFinish(): Unit = {
        log.warning(s"onDownstreamFinish $idx")
      }

      def setDisconnectedHnadler() = {
        log.warning(s"close $idx")
        fromCon.setHandler(new InHandler {
          // push from Connection
          override def onPush(): Unit = {
            val (reqCtx, response) = fromCon.grab()
            handleFailure(reqCtx, response)
          }
          override def onUpstreamFinish(): Unit = {
            toCon.complete()
            fromCon.cancel()
          }
        })
        openConnections -= this
        if (openConnections.size < minConnections) connect() // eager
      }

      toCon.setHandler(this)
      fromCon.setHandler(this)

      BidiFlow
        .fromGraph(new ZipMap[RequestContext, HttpResponse](pipeliningLimit))
        .join(
          Flow[RequestContext]
            .map(_.request)
            .buffer(pipeliningLimit, OverflowStrategies.Backpressure)
            .via(connectionFlow))
        .runWith(toCon.source, fromCon.sink)(subFusingMaterializer)

      _pullFromCon()
    }

    override def onPush(): Unit =
      if (retries.isEmpty) {
        if (pulled.nonEmpty) {
          pulled.dequeue().dispatchRequest(grab(in))
          _pull(in)
        } else if (openConnections.size < maxConnections)
          connect() // on demand
        // otherwise back-pressure
      }

    override def onPull() =
      if (responses.nonEmpty) {
        val (con, responseCtx) = responses.dequeue()
        _push(out, responseCtx)
        con._pullFromCon()
      }

    override def preStart(): Unit = {
      //setKeepGoing(true)
      while (openConnections.size < minConnections) connect() // eager
      _pull(in)
    }

    setHandlers(in, out, this)

    override def postStop(): Unit =
      log.warning("stopped poolflow")
  }

  class ZipMap[I, O](maxPending: Int) extends GraphStage[BidiShape[I, I, O, (I, Try[O])]] {
    val in = Inlet[I]("ZipMap.in1")
    val innerOut = Outlet[(I)]("ZipMap.out1")
    val innerIn = Inlet[O]("ZipMap.in2")
    val out = Outlet[(I, Try[O])]("ZipMap.out2")

    val shape = BidiShape.of(in, innerOut, innerIn, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        var failure: Throwable = null
        val queue = FixedSizeBuffer[I](maxPending)

        override def onUpstreamFinish(): Unit = {
          log.debug("onUpstreamFinishZipMapIn " + queue)
          //setKeepGoing(true)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          log.debug("onUpstreamFailureZipMapIn " + ex + queue)
          //setKeepGoing(true)
        }

        override def onPush(): Unit = {
          log.debug("onPushZipMapIn")
          val elem = grab(in)
          queue.enqueue(elem)
          push(innerOut, elem)
        }

        override def onPull(): Unit = {
          log.debug("onPullZipMapOut " + queue)
          if (!isClosed(innerIn)) {
            pull(innerIn)
          } else {
            push(out, (queue.dequeue(), Failure(failure)))
            if (queue.isEmpty) completeStage()
          }
        }

        setHandlers(innerIn, innerOut, new InHandler with OutHandler {
          override def onPull(): Unit = {
            log.debug("onPullZipMapInnerOut")
            pull(in)
          }

          override def onPush(): Unit = {
            val e = queue.dequeue()
            push(out, (e, Success(grab(innerIn))))
            log.debug("onPushZipMapInnerIn " + e)
          }

          override def onDownstreamFinish(): Unit = log.debug("onDownstreamFinishInnerOut")

          override def onUpstreamFinish(): Unit = {
            log.debug("onUpstreamFinishZipMapInnerIn queue.isEmpty=" + queue.isEmpty)
            if (queue.isEmpty)
              completeStage()
            //else
            //  setKeepGoing(true)
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            log.debug("onUpstreamFailureZipMapInnerIn " + ex + " queue.isEmpty=" + queue.isEmpty)
            if (queue.isEmpty)
              completeStage()
            // else
            //   setKeepGoing(true)
          }
        })

        setHandlers(in, out, this)

        //override def preStart(): Unit = setKeepGoing(true)

        override def postStop(): Unit = log.debug("postStopZipMap")
      }

  }

}
