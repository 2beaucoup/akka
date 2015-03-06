/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model.headers.HttpEncoding

/** Trait for A combined Encoder and Decoder. */
trait Coder extends Encoder with Decoder {
  def hasCompression: Boolean
}
