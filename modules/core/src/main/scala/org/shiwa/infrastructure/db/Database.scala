/* Этот код определяет функциональность работы с базой данных с использованием библиотеки Doobie и пула соединений HikariCP.

- `trait Database[F[_]]` - Определяет трейт `Database`, который предоставляет абстракцию для работы с базой данных и содержит
  одно поле `xa`, представляющее транзакционный объект типа `Transactor[F]`.
- `object Database` - Объект `Database`, который содержит методы для создания ресурса базы данных и создания объекта `Database`.

Описание методов:

- `def forAsync[F[_]: Async](dbConfig: DBConfig): Resource[F, Database[F]]` - Создает ресурс базы данных для асинхронного
     эффекта `F`. Метод использует `HikariConfig` для настройки параметров подключения к базе данных, создает `HikariDataSource`
     в качестве ресурса, затем создает `Transactor` с использованием `HikariDataSource`. Метод также выполняет миграции базы
     данных с помощью `Migrations.make[F](dataSource).migrate`.
- `def make[F[_]](transactor: Transactor[F]): Database[F]` - Создает объект `Database` с заданным `Transactor`. Этот метод
       используется для создания экземпляра `Database` после создания `Transactor`.

Общая цель этого кода состоит в том, чтобы предоставить абстракцию для работы с базой данных, создать и настроить
      пул соединений с помощью HikariCP, а также выполнить необходимые миграции базы данных при инициализации.*/

package org.shiwa.infrastructure.db

import cats.effect.{Async, Resource}

import org.shiwa.config.types.DBConfig

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import eu.timepit.refined.auto._

// Определение трейта Database, который предоставляет транзакционный объект для работы с базой данных
trait Database[F[_]] {
  val xa: Transactor[F]
}

object Database {

  // Создание ресурса базы данных для асинхронного эффекта F
  def forAsync[F[_]: Async](dbConfig: DBConfig): Resource[F, Database[F]] =
    for {
      dataSource <- {
        // Создание HikariConfig и HikariDataSource для настройки пула соединений с базой данных
        val config = new HikariConfig()
        config.setDriverClassName(dbConfig.driver)
        config.setJdbcUrl(dbConfig.url)
        config.setUsername(dbConfig.user)
        config.setPassword(dbConfig.password.value)

        // Создание ресурса HikariDataSource, который будет автоматически закрыт после использования
        Resource.fromAutoCloseable(Async[F].delay(new HikariDataSource(config)))
      }
      // Создание транзакционного объекта Transactor с использованием HikariDataSource
      transactor <- ExecutionContexts.fixedThreadPool[F](32).map(HikariTransactor(dataSource, _))
      // Выполнение миграций базы данных при инициализации
      _ <- Resource.eval {
        Migrations.make[F](dataSource).migrate
      }
    } yield make[F](transactor)

  // Создание объекта Database с заданным Transactor
  def make[F[_]](transactor: Transactor[F]): Database[F] = new Database[F] {
    val xa: Transactor[F] = transactor
  }
}
