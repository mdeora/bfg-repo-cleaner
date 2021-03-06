/*
 * Copyright (c) 2012 Roberto Tyley
 *
 * This file is part of 'BFG Repo-Cleaner' - a tool for removing large
 * or troublesome blobs from Git repositories.
 *
 * BFG Repo-Cleaner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BFG Repo-Cleaner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/ .
 */

package com.madgag.git.bfg.cleaner

import java.io.ByteArrayOutputStream
import scalax.io.Resource
import scala.Some
import com.madgag.git.bfg.model.TreeBlobEntry
import com.madgag.git.ThreadLocalObjectDatabaseResources
import org.eclipse.jgit.lib.Constants.OBJ_BLOB


object BlobTextModifier {

  val DefaultSizeThreshold = 1024 * 1024

}

trait BlobTextModifier extends TreeBlobModifier {

  val threadLocalObjectDBResources: ThreadLocalObjectDatabaseResources

  def lineCleanerFor(entry: TreeBlobEntry): Option[String => String]

  val charsetDetector: BlobCharsetDetector = QuickBlobCharsetDetector

  val sizeThreshold = BlobTextModifier.DefaultSizeThreshold

  override def fix(entry: TreeBlobEntry) = {

    def filterTextIn(e: TreeBlobEntry, lineCleaner: String => String): TreeBlobEntry = {
      def isDirty(line: String) = lineCleaner(line) != line

      Some(threadLocalObjectDBResources.reader().open(e.objectId)).filter(_.getSize < sizeThreshold).flatMap {
        loader =>
          Some(Resource.fromInputStream(loader.openStream())).flatMap {
            streamResource =>
              charsetDetector.charsetFor(e, streamResource).flatMap {
                charset =>
                  Some(streamResource.reader(charset)).map(_.lines(includeTerminator = true)).filter(_.exists(isDirty)).map {
                    lines =>
                      val b = new ByteArrayOutputStream(loader.getSize.toInt)

                      lines.view.map(lineCleaner).foreach(line => b.write(line.getBytes(charset)))

                      val oid = threadLocalObjectDBResources.inserter().insert(OBJ_BLOB, b.toByteArray)

                      e.copy(objectId = oid)
                  }
              }
          }
      }.getOrElse(e)
    }

    lineCleanerFor(entry) match {
      case Some(lineCleaner) => filterTextIn(entry, lineCleaner).withoutName
      case None => entry.withoutName
    }
  }
}