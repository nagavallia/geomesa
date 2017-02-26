/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.hbase.data

import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.filter.{Filter => HBaseFilter}
import org.locationtech.geomesa.hbase.utils.BatchScan
import org.locationtech.geomesa.hbase.{HBaseFilterStrategyType, HBaseQueryPlanType}
import org.locationtech.geomesa.index.utils.Explainer
import org.locationtech.geomesa.utils.collection.{CloseableIterator, SelfClosingIterator}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

sealed trait HBaseQueryPlan extends HBaseQueryPlanType {
  def filter: HBaseFilterStrategyType
  def table: TableName
  def ranges: Seq[Query]
  def clientSideFilter: Option[Filter] // TODO use scan filters?

  override def explain(explainer: Explainer, prefix: String): Unit =
    HBaseQueryPlan.explain(this, explainer, prefix)
}

object HBaseQueryPlan {

  def explain(plan: HBaseQueryPlan, explainer: Explainer, prefix: String): Unit = {
    import org.locationtech.geomesa.filter.filterToString
    explainer.pushLevel(s"${prefix}Plan: ${plan.getClass.getName}")
    explainer(s"Table: ${Option(plan.table).orNull}")
    explainer(s"Ranges (${plan.ranges.size}): ${plan.ranges.take(5).map(rangeToString).mkString(", ")}")
    explainer(s"Client-side filter: ${plan.clientSideFilter.map(filterToString).getOrElse("None")}")
    explainer.popLevel()
  }

  private def rangeToString(range: Query): String = {
    range match {
      case r: Scan => s"[${r.getStartRow.mkString("")},${r.getStopRow.mkString("")}]"
      case r: Get => s"[${r.getRow.mkString("")},${r.getRow.mkString("")}]"
    }
  }
}

// plan that will not actually scan anything
case class EmptyPlan(filter: HBaseFilterStrategyType) extends HBaseQueryPlan {
  override val table: TableName = null
  override val ranges: Seq[Query] = Seq.empty
  override val clientSideFilter: Option[Filter] = None
  override def scan(ds: HBaseDataStore): CloseableIterator[SimpleFeature] = CloseableIterator.empty
}

case class ScanPlan(sft: SimpleFeatureType,
                    filter: HBaseFilterStrategyType,
                    table: TableName,
                    ranges: Seq[Scan],
                    clientSideFilter: Option[Filter],
                    remoteFilters: Seq[HBaseFilter] = Nil,
                    entriesToFeatures: Iterator[Result] => Iterator[SimpleFeature]) extends HBaseQueryPlan {
  override def scan(ds: HBaseDataStore): CloseableIterator[SimpleFeature] = {
    val results = new BatchScan(ds.connection, table, ranges, ds.config.queryThreads, 100000, remoteFilters)
    SelfClosingIterator(entriesToFeatures(results), results.close)
  }
}

case class GetPlan(filter: HBaseFilterStrategyType,
                   table: TableName,
                   ranges: Seq[Get],
                   clientSideFilter: Option[Filter],
                   entriesToFeatures: Iterator[Result] => Iterator[SimpleFeature]) extends HBaseQueryPlan {
  override def scan(ds: HBaseDataStore): CloseableIterator[SimpleFeature] = {
    import scala.collection.JavaConversions._
    val get = ds.connection.getTable(table)
    SelfClosingIterator(entriesToFeatures(get.get(ranges).iterator), get.close)
  }
}
