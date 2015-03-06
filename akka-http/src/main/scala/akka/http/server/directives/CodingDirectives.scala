/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.model.headers._
import akka.http.coding._
import scala.collection.immutable

trait CodingDirectives {

  import BasicDirectives._
  import RouteDirectives._
  import CodingDirectives._
  import HeaderDirectives._

  // encoding

  /**
   * Rejects the request with an UnacceptedResponseEncodingRejection
   * if the given encoding is not accepted for the response.
   */
  def responseEncodingAccepted(encoding: HttpEncoding): Directive0 =
    extract(_.request.isEncodingAccepted(encoding))
      .flatMap(if (_) pass else reject(UnacceptedResponseEncodingRejection(Set(encoding))))

  /**
   * Encodes the response with the encoding that is requested by the client with the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing or empty or specifies an encoding other than
   * identity, gzip or deflate then no encoding is used.
   */
  def encodeResponse = encode(DefaultCoders)

  /**
   * Encodes the response with the encoding that is requested by the client with the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing then the response is encoded using the `first`
   * encoder.
   *
   * If the `Accept-Encoding` header is empty and `NoCoding` is part of the encoders then no
   * response encoding is used. Otherwise the request is rejected.
   */
  def encodeResponseWith(first: Coder, more: Coder*) = encode(immutable.Seq(first +: more: _*))

  private def encode(coders: immutable.Seq[Coder]): Directive0 =
    optionalHeaderValueByType[`Accept-Encoding`]().flatMap { accept ⇒
      val acceptedCoders = accept match {
        case None ⇒
          // use first defined encoder when Accept-Encoding is missing
          coders.headOption.toList
        case Some(`Accept-Encoding`(encodings)) ⇒
          // provide fallback to identity
          val withIdentity =
            if (encodings.exists {
              case HttpEncodingRange.One(HttpEncodings.identity, _) ⇒ true
              case _ ⇒ false
            }) encodings
            else encodings :+ HttpEncodings.`identity;q=MIN`
          // sort client-accepted encodings by q-Value (and orig. order) and filter by matching codecs
          val sorted = withIdentity.sortBy(e ⇒ (-e.qValue, withIdentity.indexOf(e))).toList
          sorted.flatMap { case encoding ⇒ coders.find(codec ⇒ encoding.matches(codec.encoding)) }
      }
      if (acceptedCoders.nonEmpty)
        mapResponse { response ⇒
          val compressible =
            response.status.isSuccess && response.entity.contentType.mediaType.compressible
          acceptedCoders.find(c ⇒ !c.hasCompression || compressible) match {
            case Some(coder) ⇒ coder.encode(response)
            case None        ⇒ response // FIXME: FAIL
          }
        }
      else reject(UnacceptedResponseEncodingRejection(coders.map(_.encoding).toSet))
    }

  // decoding

  /**
   * Rejects the request with an UnsupportedRequestEncodingRejection if its encoding doesn't match the given one.
   */
  def requestEncodedWith(encoding: HttpEncoding): Directive0 =
    extract(_.request.encoding).flatMap {
      case `encoding` ⇒ pass
      case _          ⇒ reject(UnsupportedRequestEncodingRejection(encoding))
    }

  /**
   * Wraps its inner Route with decoding support using the given Decoder.
   */
  def decodeRequestWith(first: Coder, more: Coder*): Directive0 = decode(immutable.Seq(first +: more: _*))

  /**
   * Decompresses the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  def decodeRequest: Directive0 = decode(DefaultCoders)

  private def decode(coders: immutable.Seq[Coder]): Directive0 =
    extract(ctx ⇒ (ctx.request.encoding, ctx.settings)) flatMap {
      case (encoding, settings) ⇒
        coders.find(_.encoding == encoding) match {
          case Some(coder) ⇒
            mapRequest(coder.decode(_, settings.decodeMaxBytesPerChunk))
          case None ⇒
            reject(UnsupportedRequestEncodingRejection(encoding))
        }
    }
}

object CodingDirectives extends CodingDirectives {
  val DefaultCoders: immutable.Seq[Coder] = immutable.Seq(NoCoding, Gzip, Deflate)
}
