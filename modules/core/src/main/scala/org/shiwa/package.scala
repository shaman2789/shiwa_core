package org

import java.security.PublicKey

import cats.effect.Async

import org.shiwa.ext.kryo._
import org.shiwa.schema.peer.PeerId
import org.shiwa.security.SecurityProvider
import org.shiwa.statechannel.StateChannelOutput

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import io.estatico.newtype.ops._ // Подключаемые пакеты для работы с newtype
// Определение объекта shiwa внутри package object
package object shiwa {
  // Определение типа данных CoreKryoRegistrationIdRange как закрытый интервал чисел от 700 до 799
  type CoreKryoRegistrationIdRange = Interval.Closed[700, 799]
  // Определение типа данных CoreKryoRegistrationId как идентификатора регистрации Kryo с использованием CoreKryoRegistrationIdRange
  type CoreKryoRegistrationId = KryoRegistrationId[CoreKryoRegistrationIdRange]
  // Определение переменной coreKryoRegistrar как карты, связывающей классы с их соответствующими идентификаторами Kryo
  val coreKryoRegistrar: Map[Class[_], CoreKryoRegistrationId] = Map(
    classOf[StateChannelOutput] -> 700
  )
  // Неявный класс PeerIdToPublicKey, расширяющий функциональность типа PeerId
  implicit class PeerIdToPublicKey(id: PeerId) {
    // Метод toPublic, преобразующий PeerId в PublicKey с использованием эффектов F, поддерживаемых Async и SecurityProvider
    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

}
