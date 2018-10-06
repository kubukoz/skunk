package skunk

package message

import scodec.Decoder

case object PortalSuspended extends BackendMessage {

  val Tag = 's'

  def decoder: Decoder[PortalSuspended.type] =
    Decoder.point(PortalSuspended)

}