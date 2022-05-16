/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.loader.fetcher

import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.ZiplineManifest
import okio.ByteString

/**
 * Fetch from the network and save to local fileSystem cache once downloaded.
 */
class FsCachingFetcher(
  private val cache: ZiplineCache,
  private val delegate: Fetcher,
) : Fetcher {
  override suspend fun fetch(
    applicationId: String,
    id: String,
    sha256: ByteString,
    url: String
  ): ByteString = cache.getOrPut(applicationId, sha256) {
    delegate.fetch(applicationId, id, sha256, url)!!
  }

  override suspend fun fetchManifest(
    applicationId: String,
    id: String,
    url: String
  ): ZiplineManifest? = try {
    delegate.fetchManifest(applicationId, id, url)
  } catch (e: Exception) {
    cache.getPinnedManifest(applicationId)
  } ?: cache.getPinnedManifest(applicationId)

  override suspend fun pin(applicationId: String, manifest: ZiplineManifest) =
    cache.pinManifest(applicationId, manifest)
}
