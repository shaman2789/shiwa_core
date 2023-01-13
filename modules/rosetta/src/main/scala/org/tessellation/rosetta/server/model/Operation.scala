/** Rosetta Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12 Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech). https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

import org.tessellation.rosetta.server.model.dag.schema.GenericMetadata

case class Operation(
  operationIdentifier: OperationIdentifier,
  /* Restrict referenced related_operations to identifier indices < the current operation_identifier.index. This ensures there exists a clear DAG-structure of relations. Since operations are one-sided, one could imagine relating operations in a single transfer or linking operations in a call tree. */
  relatedOperations: Option[List[OperationIdentifier]],
  /* Type is the network-specific type of the operation. Ensure that any type that can be returned here is also specified in the NetworkOptionsResponse. This can be very useful to downstream consumers that parse all block data. */
  `type`: String,
  /* Status is the network-specific status of the operation. Status is not defined on the transaction object because blockchains with smart contracts may have transactions that partially apply (some operations are successful and some are not). Blockchains with atomic transactions (all operations succeed or all operations fail) will have the same status for each operation. On-chain operations (operations retrieved in the `/block` and `/block/transaction` endpoints) MUST have a populated status field (anything on-chain must have succeeded or failed). However, operations provided during transaction construction (often times called \"intent\" in the documentation) MUST NOT have a populated status field (operations yet to be included on-chain have not yet succeeded or failed). */
  status: Option[String],
  account: Option[AccountIdentifier],
  amount: Option[Amount],
  coinChange: Option[CoinChange],
  metadata: Option[GenericMetadata]
)
