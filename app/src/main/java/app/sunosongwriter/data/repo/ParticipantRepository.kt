package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.ParticipantDao
import app.sunosongwriter.domain.model.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class ParticipantRepository(
    private val dao: ParticipantDao,
    json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mappers = Mappers(json)

    val participants: Flow<List<Participant>> = dao.observeAll().map { list -> list.map(mappers::toDomain) }

    suspend fun get(id: Long): Participant? = dao.get(id)?.let(mappers::toDomain)

    suspend fun save(participant: Participant): Long =
        if (participant.id == 0L) {
            dao.insert(mappers.toEntity(participant, nowMillis()))
        } else {
            val createdAt = dao.get(participant.id)?.createdAt ?: nowMillis()
            dao.update(mappers.toEntity(participant, createdAt))
            participant.id
        }

    suspend fun delete(id: Long) = dao.delete(id)
}
