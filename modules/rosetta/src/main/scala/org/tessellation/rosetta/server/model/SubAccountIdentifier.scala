/** Rosetta Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12 Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech). https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

import org.tessellation.rosetta.server.model.dag.metadataSchema.GenericMetadata

case class SubAccountIdentifier(
  /* The SubAccount address may be a cryptographic value or some other identifier (ex: bonded) that uniquely specifies a SubAccount. */
  address: String,
  /* If the SubAccount address is not sufficient to uniquely specify a SubAccount, any other identifying information can be stored here. It is important to note that two SubAccounts with identical addresses but differing metadata will not be considered equal by clients. */
  metadata: Option[GenericMetadata]
)