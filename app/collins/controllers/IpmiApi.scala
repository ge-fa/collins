package collins.controllers

import java.sql.SQLException

import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.optional
import play.api.data.Forms.text
import play.api.http.{ Status => StatusValues }
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsObject
import play.api.mvc.Results

import collins.controllers.actions.ipmi.GetPoolsAction

import collins.models.Asset
import collins.models.Truthy
import collins.models.IpmiInfo
import collins.models.shared.AddressPool
import collins.util.IpAddress

trait IpmiApi {
  this: Api with SecureController =>

  case class IpmiCreateForm(pool: Option[String])

  case class IpmiUpdateForm(username: Option[String], password: Option[String], address: Option[String], gateway: Option[String], netmask: Option[String]) {
    def merge(asset: Asset, ipmi: Option[IpmiInfo]): IpmiInfo = {
      ipmi.map { info =>
        val iu: IpmiInfo = username.map(u => info.copy(username = u)).getOrElse(info)
        val pu: IpmiInfo = password.map(p => iu.copy(password = IpmiInfo.encryptPassword(p))).getOrElse(iu)
        val au: IpmiInfo = address.map(a => pu.copy(address = IpAddress.toLong(a))).getOrElse(pu)
        val gu: IpmiInfo = gateway.map(g => au.copy(gateway = IpAddress.toLong(g))).getOrElse(au)
        netmask.map(n => gu.copy(netmask = IpAddress.toLong(n))).getOrElse(gu)
      }.getOrElse {
        val a = IpAddress.toLong(address.get)
        val g = IpAddress.toLong(gateway.get)
        val n = IpAddress.toLong(netmask.get)
        val p = IpmiInfo.encryptPassword(password.get)
        IpmiInfo(asset.id, username.get, p, g, a, n)
      }
    }
  }

  // TODO: extend form to include the ipmi network name if desired
  val IPMI_UPDATE_FORM = Form(
    mapping(
      "username" -> optional(text(1)),
      "password" -> optional(text(minLength=4, maxLength=20)),
      "address" -> optional(text(7)),
      "gateway" -> optional(text(7)),
      "netmask" -> optional(text(7))
    )(IpmiUpdateForm.apply)(IpmiUpdateForm.unapply)
  )

  val IPMI_CREATE_FORM = Form(
    mapping(
      "pool" -> optional(text(1))
    )(IpmiCreateForm.apply)(IpmiCreateForm.unapply)
  )

  def generateIpmi(tag: String) = SecureAction { implicit req =>
    Api.withAssetFromTag(tag) { asset =>
      IPMI_CREATE_FORM.bindFromRequest.fold(
        hasErrors => {
          val error = hasErrors.errors.map { _.message }.mkString(", ")
          Left(Api.getErrorMessage("Data submission error: %s".format(error)))
        },
        ipmiForm => {
          try {
            ipmiForm match {
              case IpmiCreateForm(poolOption) => IpmiInfo.findByAsset(asset) match {
                case Some(ipmiinfo) =>
                  Left(Api.getErrorMessage("Asset already has IPMI details, cannot generate new IPMI details", Results.BadRequest))
                case None => IpmiInfo.getConfig(poolOption) match {
                  case None =>
                    Left(Api.getErrorMessage("Invalid IPMI pool %s specified".format(poolOption.getOrElse("default")), Results.BadRequest))
                  case Some(AddressPool(poolName, _, _, _)) =>
                    // make sure asset does not already have IPMI created, because this
                    // implies we want to create new IPMI details
                    val info = IpmiInfo.findByAsset(asset)
                    val newInfo = IpmiInfo.createForAsset(asset, poolOption)
                    tattler(None).notice("Generated IPMI configuration from %s: IP %s, Netmask %s, Gateway %s".format(
                      poolName, newInfo.dottedAddress, newInfo.dottedNetmask, newInfo.dottedGateway), asset)
                    Right(ResponseData(Results.Created, JsObject(Seq("SUCCESS" -> JsBoolean(true)))))
                }
              }
            }
          } catch {
            case e: SQLException =>
              Left(Api.getErrorMessage("Possible duplicate IPMI Address",
                Results.Status(StatusValues.CONFLICT)))
            case e: Throwable =>
              Left(Api.getErrorMessage("Incomplete form submission: %s".format(e.getMessage)))
          }
        }
      )
    }.fold(
      err => formatResponseData(err),
      suc => formatResponseData(suc)
    )
  }(Permissions.IpmiApi.GenerateIpmi)


  def updateIpmi(tag: String) = SecureAction { implicit req =>
    Api.withAssetFromTag(tag) { asset =>
      IPMI_UPDATE_FORM.bindFromRequest.fold(
        hasErrors => {
          val error = hasErrors.errors.map { _.message }.mkString(", ")
          Left(Api.getErrorMessage("Data submission error: %s".format(error)))
        },
        ipmiForm => {
          try {
            val newInfo = ipmiForm.merge(asset, IpmiInfo.findByAsset(asset))
            val (status, success) = newInfo.id match {
              case update if update > 0 =>
                IpmiInfo.update(newInfo) match {
                  case 0 => (Results.Conflict, false)
                  case _ => (Results.Ok, true)
                }
              case _ =>
                (Results.Created, IpmiInfo.create(newInfo).id > 0)
            }
            if (status == Results.Conflict) {
              Left(Api.getErrorMessage("Unable to update IPMI information",status))
            } else {
              tattler(None).notice("Updated IPMI configuration: IP %s, Netmask %s, Gateway %s".format(
                newInfo.dottedAddress, newInfo.dottedNetmask, newInfo.dottedGateway), asset)
              Right(ResponseData(status, JsObject(Seq("SUCCESS" -> JsBoolean(success)))))
            }
          } catch {
            case e: SQLException =>
              Left(Api.getErrorMessage("Possible duplicate IPMI Address",
                Results.Status(StatusValues.CONFLICT)))
            case e: Throwable =>
              Left(Api.getErrorMessage("Incomplete form submission: %s".format(e.getMessage)))
          }
        }
      )
    }.fold(
      err => formatResponseData(err),
      suc => formatResponseData(suc)
    )
  }(Permissions.IpmiApi.UpdateIpmi)

  // GET /api/ipmi/pools
  def getIpmiAddressPools() =
    GetPoolsAction(Permissions.IpmiApi.GetAddressPools, this)

}
