package me.rerere.rikkahub.data.favorite

import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.model.FavoriteMeta
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteRef
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.buildFavoritePreview
import me.rerere.rikkahub.utils.JsonInstant

object NodeFavoriteAdapter : FavoriteAdapter<NodeFavoriteTarget> {
    override val type: FavoriteType = FavoriteType.NODE

    override fun buildRefKey(target: NodeFavoriteTarget): String {
        return buildRefKey(target.conversationId.toString(), target.nodeId.toString())
    }

    fun buildRefKey(conversationId: String, nodeId: String): String = "node:$conversationId:$nodeId"

    override fun buildFavoriteEntity(
        target: NodeFavoriteTarget,
        existing: FavoriteEntity?,
        now: Long
    ): FavoriteEntity {
        return buildFavoriteEntity(target, existing, null, now)
    }

    fun buildFavoriteEntity(
        target: NodeFavoriteTarget,
        existing: FavoriteEntity?,
        ttsBookmark: me.rerere.tts.controller.TtsBookmarkInfo?,
        now: Long,
    ): FavoriteEntity {
        val ref = NodeFavoriteRef(
            conversationId = target.conversationId,
            nodeId = target.nodeId,
        )
        val meta = FavoriteMeta(
            title = target.conversationTitle.ifBlank { null },
            subtitle = target.nodeId.toString(),
            previewText = target.node.buildFavoritePreview(),
        )

        val snapshotJson = if (ttsBookmark != null) {
            JsonInstant.encodeToString(ttsBookmark)
        } else {
            ""
        }

        return FavoriteEntity(
            id = existing?.id ?: buildRefKey(target),
            type = type.value,
            refKey = buildRefKey(target),
            refJson = JsonInstant.encodeToString(ref),
            snapshotJson = snapshotJson,
            metaJson = JsonInstant.encodeToString(meta),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    fun decodeRef(entity: FavoriteEntity): NodeFavoriteRef? {
        if (entity.type != type.value) return null
        return runCatching {
            JsonInstant.decodeFromString<NodeFavoriteRef>(entity.refJson)
        }.getOrNull()
    }

    fun decodeMeta(entity: FavoriteEntity): FavoriteMeta? {
        if (entity.type != type.value) return null
        val rawMeta = entity.metaJson ?: return null
        return runCatching {
            JsonInstant.decodeFromString<FavoriteMeta>(rawMeta)
        }.getOrNull()
    }

    fun decodeTtsBookmark(entity: FavoriteEntity): me.rerere.tts.controller.TtsBookmarkInfo? {
        if (entity.type != type.value) return null
        val raw = entity.snapshotJson ?: return null
        if (raw.isBlank()) return null
        return runCatching {
            JsonInstant.decodeFromString<me.rerere.tts.controller.TtsBookmarkInfo>(raw)
        }.getOrNull()
    }
}
