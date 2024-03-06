/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.room.integration.multiplatformtestapp.test

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Entity
data class SampleEntity(
    @PrimaryKey
    val pk: Long,
    @ColumnInfo(defaultValue = "0")
    val data: Long
)

@Dao
interface SampleDao {

    @Query("INSERT INTO SampleEntity (pk) VALUES (:pk)")
    suspend fun insertItem(pk: Long): Long

    @Query("DELETE FROM SampleEntity WHERE pk = :pk")
    suspend fun deleteItem(pk: Long): Int

    @Query("SELECT * FROM SampleEntity")
    suspend fun getSingleItem(): SampleEntity

    @Query("SELECT * FROM SampleEntity")
    suspend fun getItemList(): List<SampleEntity>

    @Transaction
    suspend fun deleteList(pks: List<Long>, withError: Boolean = false) {
        require(!withError)
        pks.forEach { deleteItem(it) }
    }

    @Query("SELECT * FROM SampleEntity")
    suspend fun getSingleItemWithColumn(): SampleEntity

    @Insert
    suspend fun insert(entity: SampleEntity)

    @Upsert
    suspend fun upsert(entity: SampleEntity)

    @Delete
    suspend fun delete(entity: SampleEntity)

    @Update
    suspend fun update(entity: SampleEntity)
}

@Database(
    entities = [SampleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SampleDatabase : RoomDatabase() {
    abstract fun dao(): SampleDao
}
