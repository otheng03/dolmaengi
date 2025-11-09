package dolmeangi.kotlin.common.transaction

interface SequenceGenerator {

    suspend fun getNext(): Long

    suspend fun getCurrent(): Long

}