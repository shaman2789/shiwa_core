/* Этот код определяет функциональность выполнения миграций базы данных с использованием библиотеки Flyway.

- `trait Migrations[F[_]]` - Определяет трейт `Migrations`, который предоставляет абстракцию для выполнения миграций
    базы данных. Он содержит метод `migrate`, который выполняет миграции и возвращает результат типа `F[MigrateResult]`.
- `object Migrations` - Объект `Migrations`, который содержит метод `make` для создания объекта `Migrations` и выполнения миграций.

Описание методов:

- `def make[F[_]: Async](dataSource: DataSource): Migrations[F]` - Создает объект `Migrations` для асинхронного эффекта `F`
    с использованием указанного `DataSource`. Метод создает экземпляр `Flyway` с использованием указанного `DataSource`, а затем
    возвращает объект `Migrations` с реализацией метода `migrate`, который вызывает `flyway.migrate()` и возвращает результат в виде
    `F[MigrateResult]`.*/

package org.shiwa.infrastructure.db

import javax.sql.DataSource

import cats.effect.Async

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

// Определение трейта Migrations, который предоставляет функциональность выполнения миграций базы данных
trait Migrations[F[_]] {
  def migrate: F[MigrateResult]
}

object Migrations {

  // Создание объекта Migrations для асинхронного эффекта F с использованием указанного DataSource
  def make[F[_]: Async](dataSource: DataSource): Migrations[F] = new Migrations[F] {
    // Создание экземпляра Flyway с указанным DataSource
    private val flyway = Flyway
      .configure()
      .dataSource(dataSource)
      .load()

    // Выполнение миграций базы данных и возвращение результата в виде F[MigrateResult]
    override def migrate: F[MigrateResult] = Async[F].delay(flyway.migrate())
  }

}
