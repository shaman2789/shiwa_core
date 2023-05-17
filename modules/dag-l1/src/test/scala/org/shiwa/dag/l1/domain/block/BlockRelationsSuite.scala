package org.shiwa.dag.l1.domain.block

import cats.effect.{IO, Resource}

import org.shiwa.block.generators._
import org.shiwa.dag.l1.Main
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.transaction
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.sdk.sdkKryoRegistrar
import org.shiwa.security.Hashed
import org.shiwa.security.signature.Signed

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockRelationsSuite extends MutableIOSuite with Checkers {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, BlockServiceSuite.Res] =
    KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar)

  test("when no relation between blocks then block is independent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block is parent of second then block is dependent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block is parent of first then block is independent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        notRelatedHashedBlock <- notRelatedBlock.toHashed[IO]
        hashedBlock <- block.copy(value = block.value.copy(parent = notRelatedHashedBlock.ownReference :: block.value.parent)).toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block has transaction reference used in second then block is dependent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        hashedTxn <- block.transactions.head.toHashed[IO]
        relatedTxn = notRelatedBlock.transactions.head.copy(value =
          notRelatedBlock.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
        )
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block has transaction reference used in first then block is independent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedTxn <- notRelatedBlock.transactions.head.toHashed[IO]
        blockTxn = block.transactions.head
          .copy(value = block.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash)))
        hashedBlock <- block.copy(value = block.value.copy(transactions = block.transactions.add(blockTxn))).toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block sends transaction to second then block is dependent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) => BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](hashedBlock)(block)
        txn = block.transactions.head
        relatedTxn = notRelatedBlock.transactions.head.copy(value = notRelatedBlock.transactions.head.value.copy(source = txn.destination))
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when blocks is related with block reference then block is dependent") { implicit ks =>
    forall { (block: Signed[SHIBlock], notRelatedBlock: Signed[SHIBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[SHIBlock]) =>
          BlockRelations.dependsOn[IO, SHITransaction, SHIBlock](Set.empty[Hashed[SHIBlock]], Set(hashedBlock.ownReference))(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

}