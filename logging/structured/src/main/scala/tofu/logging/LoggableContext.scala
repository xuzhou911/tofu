package tofu
package logging

/** proof that F has contextual value and it is loggable */
trait LoggableContext[F[_]] {
  type Ctx
  implicit def loggable: Loggable[Ctx]
  implicit def context: F HasContext Ctx
}

object LoggableContext {
  def of[F[_]] = new LoggableContextPA[F]
  private[logging] final class LoggableContextPA[F[_]](private val dummy: Boolean = true) extends AnyVal {
    def instance[C](implicit ctx: F HasContext C, ctxLog: Loggable[C]): LoggableContext[F] = new LoggableContext[F] {
      type Ctx = C
      val loggable: Loggable[C]   = ctxLog
      val context: F HasContext C = ctx
    }
  }
}
