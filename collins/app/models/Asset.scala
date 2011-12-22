package models

import Model.defaults._
import conversions._
import util.{Helpers, LshwRepresentation}

import anorm._
import play.api.json._

import java.sql.{Connection, Timestamp}
import java.util.Date

case class Asset(
    id: Pk[java.lang.Long],
    tag: String,
    status: Int,
    asset_type: Int,
    created: Timestamp, updated: Option[Timestamp], deleted: Option[Timestamp])
{
  require(Asset.isValidTag(tag), "Tag must be non-empty alpha numeric")

  def toMap(): Map[String,String] = Map(
    "ID" -> getId().toString,
    "TAG" -> tag,
    "STATUS" -> getStatus().name,
    "TYPE" -> getType().name,
    "CREATED" -> Helpers.dateFormat(created),
    "UPDATED" -> updated.map { Helpers.dateFormat(_) }.getOrElse("")
  )
  def toJsonMap(): Map[String,JsValue] = Map(
    "ID" -> JsNumber(getId()),
    "TAG" -> JsString(tag),
    "STATUS" -> JsString(getStatus().name),
    "TYPE" -> JsString(getType().name),
    "CREATED" -> JsString(Helpers.dateFormat(created)),
    "UPDATED" -> JsString(updated.map { Helpers.dateFormat(_) }.getOrElse(""))
  )

  def getId(): Long = id.get
  def isNew(): Boolean = {
    status == models.Status.Enum.New.id
  }
  def getStatus(): Status = {
    Status.findById(status).get
  }
  def getType(): AssetType = {
    AssetType.findById(asset_type).get
  }
  def getAttribute(spec: AssetMeta.Enum): Option[MetaWrapper] = {
    AssetMetaValue.findOneByAssetId(Set(spec), id.get).toList match {
      case Nil => None
      case one :: Nil =>
        Some(one)
      case other =>
        throw new IndexOutOfBoundsException("Expected one value, if any")
    }
  }
  def getAttributes(specs: Set[AssetMeta.Enum] = Set.empty): List[MetaWrapper] = {
    specs.isEmpty match {
      case true =>
        AssetMetaValue.findAllByAssetId(id.get).toList
      case false =>
        AssetMetaValue.findOneByAssetId(specs, id.get).toList
    }
  }
}

object Asset extends Magic[Asset](Some("asset")) {

  private[this] val TagR = """[A-Za-z0-9\-_]+""".r.pattern.matcher(_)
  def isValidTag(tag: String): Boolean = {
    tag != null && tag.nonEmpty && TagR(tag).matches
  }

  def apply(tag: String, status: Status.Enum, asset_type: AssetType.Enum) = {
    new Asset(NotAssigned, tag, status.id, asset_type.id, new Date().asTimestamp, None, None)
  }

  def apply(tag: String, status: Status.Enum, asset_type: AssetType) = {
    new Asset(NotAssigned, tag, status.id, asset_type.getId, new Date().asTimestamp, None, None)
  }

  def create(assets: Seq[Asset])(implicit con: Connection): Seq[Asset] = {
    assets.foldLeft(List[Asset]()) { case(list, asset) =>
      if (asset.id.isDefined) throw new IllegalArgumentException("id of asset must be NotAssigned")
      Asset.create(asset) +: list
    }.reverse
  }

  def findById(id: Long): Option[Asset] = Model.withConnection { implicit con =>
    Asset.find("id={id}").on('id -> id).singleOption()
  }
  def findByTag(tag: String): Option[Asset] = Model.withConnection { implicit con =>
    Asset.find("tag={tag}").on('tag -> tag).first()
  }
  def findLikeTag(tag: String): Seq[Asset] = Model.withConnection { implicit con =>
    Asset.find("tag like {tag}").on('tag -> (tag + "%")).list()
  }

  def findByMeta(list: Seq[(AssetMeta.Enum,String)]): Seq[Asset] = {
    val query = "select distinct asset_id from asset_meta_value where "
    var count = 0
    val params = list.map { case(k,v) =>
      val id: String = k.toString + "_" + count
      count += 1
      val fragment = "asset_meta_value.asset_meta_id = %d and asset_meta_value.value like {%s}".format(k.id, id)
      (fragment, (Symbol(id), toParameterValue(v)))
    }
    val subquery = query + params.map { _._1 }.mkString(" and ")
    Model.withConnection { implicit connection =>
      Asset.find("select * from asset WHERE id in (%s)".format(subquery)).on(
        params.map(_._2):_*
      ).list()
    }
  }
}

case class AssetFinder(
  status: Option[Status.Enum],
  assetType: Option[AssetType.Enum],
  createdAfter: Option[Date],
  createdBefore: Option[Date],
  updatedAfter: Option[Date],
  updatedBefore: Option[Date])
{
  // Without this, toParameterValue sees dates as java.util.Date instead of Timestamp and the wrong
  // ToStatement is used
  import DaoSupport._

  type Intable = {def id: Int}
  def asQueryFragment(): SimpleSql[Row] = {
    val _status = getEnumSimple("asset.status", status)
    val _atype = getEnumSimple("asset.asset_type", assetType)
    val _created = createDateSimple("asset.created", createdAfter, createdBefore)
    val _updated = createDateSimple("asset.updated", updatedAfter, updatedBefore)
    flattenSql(Seq(_status, _atype, _created, _updated).collect { case Some(i) => i })
  }

  private def getEnumSimple(param: String, enum: Option[Intable]): Option[SimpleSql[Row]] = {
    enum.map { e =>
      val name = "%s_0".format(param.replace(".","_"));
      SqlQuery("%s={%s}".format(param, name)).on(name -> e.id)
    }
  }
  private def createDateSimple(param: String, after: Option[Date], before: Option[Date]): Option[SimpleSql[Row]] = {
    val afterName = "%s_after_0".format(param.replace(".","_"))
    val beforeName = "%s_before_0".format(param.replace(".","_"))
    val _after = after.map { date =>
      SqlQuery("%s >= {%s}".format(param, afterName)).on(afterName -> date.asTimestamp)
    }
    val _before = before.map { date =>
      SqlQuery("%s <= {%s}".format(param, beforeName)).on(beforeName -> date.asTimestamp)
    }
    val filtered: Seq[SimpleSql[Row]] = Seq(_after, _before).collect { case Some(i) => i }
    if (filtered.nonEmpty) {
      Some(flattenSql(filtered))
    } else {
      None
    }
  }
}