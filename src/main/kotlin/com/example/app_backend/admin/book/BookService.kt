package com.example.app_backend.admin.book

import com.example.app_backend.admin.rabbit.HitsRecords
import com.example.app_backend.admin.user.Users
import com.example.app_backend.api.SimplifiedBooks
import com.example.app_backend.api.TodayBooks
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
// JSON 문자열을 코틀린으로
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.json.JsonParseException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class BookService(
        // 중간 저장소
        // String 타입의 키와 값으로 데이터를 저장하거나 조회
        private val redisTemplate: RedisTemplate<String, String>

) {
    // Java 객체와 JSON 문자열 간의 변환
    private val mapper = jacksonObjectMapper()
    fun checkLast(cacheData: Any?) {
        when (cacheData) {
            // 매개변수가 string일때
            is String -> {
                if (cacheData != null) {
                    val books: List<SimplifiedBookDTO> = mapper.readValue(cacheData)
                    val lastBook = books.lastOrNull()
                    println("Last book: $lastBook")
                }
            }

            is List<*> -> {
                val lastBook = cacheData.lastOrNull()
//                println("Last updated book: $lastBook")
            }

            else -> {
                println("Unsupported type")
            }
        }
    }

    // redisTemplate 방식
    fun getCacheBooks(): List<SimplifiedBookDTO> {
//        println("cache 요청 들어 옴")
        // 성능 측정 시작 시간
        val start = System.currentTimeMillis()
        // Redis 캐시에서 "books"라는 키로 저장된 데이터를 가져옴.
        val cacheData = redisTemplate.opsForValue().get("books")
        println("2.조회: redis cache 업데이트 확인")
        checkLast(cacheData)


        val booksFromDB = getBooks()
        return if (!cacheData.isNullOrEmpty()) {
            val booksFromCache = try {
//               readValue: JSON 문자열을 코틀린의 리스트 객체로 변환
                //typeOf<T>(cacheData)
                mapper.readValue<List<SimplifiedBookDTO>>(cacheData)
            } catch (e: JsonParseException) {
                println("Json parsing error: ${e.message}")
                emptyList<SimplifiedBookDTO>()
            }

            // 캐시 데이터와 DB 데이터 일치 여부 확인
            if (booksFromCache == booksFromDB) {
                println("Data from Redis")
                val cacheData = redisTemplate.opsForValue().get("books")
                println("3. redis cache 업데이트 확인")
                checkLast(cacheData)
                booksFromCache
            } else {
                println("Data from MySQL")
                // 캐시 데이터와 DB 데이터 불일치 시, 캐시 업데이트
                redisTemplate.opsForValue().set("books", mapper.writeValueAsString(booksFromDB))
                val cacheData = redisTemplate.opsForValue().get("books")
                println("DB와 일치 하지 않을떄")
                checkLast(cacheData)
                booksFromDB
            }
        } else {
            println("Data from MySQL")
            // 캐시에 데이터 없을 시, 캐시 업데이트
            val cacheData = redisTemplate.opsForValue().set("books", mapper.writeValueAsString(booksFromDB))
            checkLast(cacheData)
            println("redis null일때:")
            booksFromDB
        }.also {
            val end = System.currentTimeMillis()
            println("Time taken: ${end - start} ms")
        }
    }

    fun updateCache(newBook: SimplifiedBookDTO? = null, deletedItemIds: List<Int>? = null): List<SimplifiedBookDTO> {
        println("updateCache 요청 들어 옴")
        // Redis 캐시에서 "books"라는 키로 저장된 데이터를 가져옴.
        val cacheData = redisTemplate.opsForValue().get("books")
        val booksFromCache = if (cacheData != null) {
            mapper.readValue<List<SimplifiedBookDTO>>(cacheData)
        } else {
            emptyList()
        }

        if (deletedItemIds != null) {
            println("updateCache 삭제 요청 옴.")
            // db 가져옴

            // 일치하는 것 제외하고 새로운 데이터로 set함.
            val updatedBooks = booksFromCache.filterNot { it.itemId in deletedItemIds }
            redisTemplate.opsForValue().set("books", mapper.writeValueAsString(updatedBooks))
//            println("Updated cache: ${redisTemplate.opsForValue().get("books")}")
            return updatedBooks
        }

        if (newBook != null) {
            val isNewBook = booksFromCache.none { it.itemId == newBook.itemId }
            val isExistingBook = booksFromCache.any { it.itemId == newBook.itemId }

            if (isNewBook) {
                println("updateCache 추가 요청 옴")
                // 새로운 책을 추가
                val updatedBooks = booksFromCache + newBook
                redisTemplate.opsForValue().set("books", mapper.writeValueAsString(updatedBooks))
//                println("Updated cache with new book: ${redisTemplate.opsForValue().get("books")}")
                return updatedBooks
            } else if (isExistingBook) {
                println("updateCache 수정 요청 옴")
                // 기존 책 정보를 삭제하고, 수정된 책 정보를 추가
                val updatedBooks = booksFromCache.map {
                    if (it.itemId == newBook.itemId) newBook else it
                }
                redisTemplate.opsForValue().set("books", mapper.writeValueAsString(updatedBooks))
//                println("Updated cache with updated book: ${redisTemplate.opsForValue().get("books")}")
                return updatedBooks
            }
        }
        // 아무런 변경이 없으면 기존 정보 반환
        return getBooks()
    }


    // DB
    fun getBooks(): List<SimplifiedBookDTO> {
        println("getBooks() called")//함수 호출
        return transaction {
            SimplifiedBooks.selectAll().map { row ->
                SimplifiedBookDTO(
                        id = row[SimplifiedBooks.id].value,
                        createdDate = row[SimplifiedBooks.createdDate],
                        publisher = row[SimplifiedBooks.publisher],
                        title = row[SimplifiedBooks.title],
                        link = row[SimplifiedBooks.link],
                        author = row[SimplifiedBooks.author],
                        pubDate = row[SimplifiedBooks.pubDate],
                        description = row[SimplifiedBooks.description],
                        isbn = row[SimplifiedBooks.isbn],
                        isbn13 = row[SimplifiedBooks.isbn13],
                        itemId = row[SimplifiedBooks.itemId],
                        priceSales = row[SimplifiedBooks.priceSales],
                        priceStandard = row[SimplifiedBooks.priceStandard],
                        stockStatus = row[SimplifiedBooks.stockStatus],
                        cover = row[SimplifiedBooks.cover],
                        categoryId = row[SimplifiedBooks.categoryId],
                        categoryName = row[SimplifiedBooks.categoryName],
                        customerReviewRank = row[SimplifiedBooks.customerReviewRank]
                )
            }
        }
    }

    val currentTime = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")
    val formattedDate = currentTime.format(formatter)

    //mysql DB
    fun addBook(book: SimplifiedBookDTO): List<SimplifiedBookDTO> {
        println("addBook 요청 들어옴")
        val existingBooks = getBooks()
        val isBook = existingBooks.filter { it.itemId == book.itemId }
        if (isBook.isEmpty()) {
            // DB에 책을 추가
            val addBook = transaction {
                SimplifiedBooks.insertAndGetId {
                    it[createdDate] = formattedDate
                    it[title] = book.title
                    it[link] = book.link
                    it[author] = book.author
                    it[pubDate] = book.pubDate
                    it[description] = book.description
                    it[isbn] = book.isbn
                    it[isbn13] = book.isbn13
                    it[itemId] = book.itemId
                    it[priceSales] = book.priceSales
                    it[priceStandard] = book.priceStandard
                    it[stockStatus] = book.stockStatus
                    it[cover] = book.cover
                    it[categoryId] = book.categoryId
                    it[categoryName] = book.categoryName
                    it[publisher] = book.publisher
                    it[customerReviewRank] = book.customerReviewRank
                }
            }
            //데이터베이스에 책을 추가한 후, 반환된 ID(addBook)를 사용-> 자동생성기능도 업데이트
            // 새로운 SimplifiedBookDTO 객체를 생성
            val newBook = book.copy(id = addBook.value, createdDate = formattedDate)
            return updateCache(newBook)
        }
        return emptyList()
    }

    fun getBookByItemId(itemId: Int): SimplifiedBookDTO? {
        return transaction {
            SimplifiedBooks.select { SimplifiedBooks.itemId eq itemId }
                    .map { row ->
                        SimplifiedBookDTO(
                                id = row[SimplifiedBooks.id].value,
                                createdDate = row[SimplifiedBooks.createdDate],
                                publisher = row[SimplifiedBooks.publisher],
                                title = row[SimplifiedBooks.title],
                                link = row[SimplifiedBooks.link],
                                author = row[SimplifiedBooks.author],
                                pubDate = row[SimplifiedBooks.pubDate],
                                description = row[SimplifiedBooks.description],
                                isbn = row[SimplifiedBooks.isbn],
                                isbn13 = row[SimplifiedBooks.isbn13],
                                itemId = row[SimplifiedBooks.itemId],
                                priceSales = row[SimplifiedBooks.priceSales],
                                priceStandard = row[SimplifiedBooks.priceStandard],
                                stockStatus = row[SimplifiedBooks.stockStatus],
                                cover = row[SimplifiedBooks.cover],
                                categoryId = row[SimplifiedBooks.categoryId],
                                categoryName = row[SimplifiedBooks.categoryName],
                                customerReviewRank = row[SimplifiedBooks.customerReviewRank]
                        )
                    }
                    .singleOrNull() // 여기서 singleOrNull()을 사용하여 해당하는 데이터가 없을 경우 null을 반환하도록 함
        }
    }

    fun deleteBooks(itemIds: List<Int>): List<SimplifiedBookDTO> {
        println("deleteBooks 요청 들어옴")
        println("삭제 요청 리스트 데이터:${itemIds}")
        // DB에서 책을 삭제
        transaction {
            SimplifiedBooks.deleteWhere { SimplifiedBooks.itemId inList itemIds }
        }
//        println("deleteBooks 응답:${updateCache(deletedItemIds = itemIds)} ")
        // 삭제된 캐시 업데이트
        return updateCache(deletedItemIds = itemIds)
    }

    fun modifyBook(itemId: Int, updatedData: SimplifiedBookDTO): SimplifiedBookDTO {
        // DB에서 책 정보를 업데이트
        val updatedBook = transaction {
            SimplifiedBooks.update({ SimplifiedBooks.itemId eq itemId }) {
                it[createdDate] = formattedDate
                if (updatedData.title != null) it[title] = updatedData.title
                if (updatedData.link != null) it[link] = updatedData.link
                if (updatedData.author != null) it[author] = updatedData.author
                if (updatedData.pubDate != null) it[pubDate] = updatedData.pubDate
                if (updatedData.description != null) it[description] = updatedData.description
                if (updatedData.isbn != null) it[isbn] = updatedData.isbn
                if (updatedData.isbn13 != null) it[isbn13] = updatedData.isbn13
//                if (updatedData.itemId != null) it[itemId] = updatedData.itemId
                if (updatedData.priceSales != null) it[priceSales] = updatedData.priceSales
                if (updatedData.priceStandard != null) it[priceStandard] = updatedData.priceStandard
                if (updatedData.stockStatus != null) it[stockStatus] = updatedData.stockStatus
                if (updatedData.cover != null) it[cover] = updatedData.cover
                if (updatedData.categoryId != null) it[categoryId] = updatedData.categoryId
                if (updatedData.categoryName != null) it[categoryName] = updatedData.categoryName
                if (updatedData.publisher != null) it[publisher] = updatedData.publisher
                if (updatedData.customerReviewRank != null) it[customerReviewRank] = updatedData.customerReviewRank
            }
        }
        // 업데이트된 책의 정보를 반환
        val updatedBookData = getBookByItemId(itemId) ?: throw Exception("Book not found")

        // 캐시 업데이트
        updateCache(updatedBookData)

        // 업데이트된 책 정보 반환
        return updatedBookData
    }


    fun addTodayBook(book: TodayBookDTO): TodayBookDTO {
        transaction {
            TodayBooks.insert {
                it[title] = book.title
                it[author] = book.author
                it[priceSales] = book.priceSales
                it[cover] = book.cover
                it[todayLetter] = book.todayLetter
                it[itemId] = book.itemId
                it[readData] = book.readDate
            }
        }

        println("추가된 오늘의 도서 정보: ${book}")
        return book
    }

    fun getTodayBooks(readDate: String? = null): TodayBookDTO? {
        println("getTodayBooks() called")
        return transaction {
            val query = if (readDate != null) {
                TodayBooks.select { TodayBooks.readData eq readDate }
            } else {
                TodayBooks.selectAll()
            }
            query.map { row ->
                TodayBookDTO(
                        title = row[TodayBooks.title],
                        author = row[TodayBooks.author],
                        priceSales = row[TodayBooks.priceSales],
                        cover = row[TodayBooks.cover],
                        todayLetter = row[TodayBooks.todayLetter],
                        itemId = row[TodayBooks.itemId],
                        readDate = row[TodayBooks.readData]
                )
            }
                    .singleOrNull()
        }
    }

    //통계조회
    // 도서 DB 없을때 오류
    val errorItemIds = mutableSetOf<Int>()
    fun processBookView(itemId: Int) {
        val book = findBookByItemId(itemId)
        if (book == null) {
            errorItemIds.add(itemId)
            if (errorItemIds.size >= ERROR_THRESHOLD) {
                reportErrorToAdmin(errorItemIds)
                errorItemIds.clear()
            }
        } else {
            findBookByItemId(itemId)
        }
    }

    fun findBookByItemId(itemId: Int): Int? {
        return SimplifiedBooks.select { SimplifiedBooks.itemId eq itemId }
                .singleOrNull()
                ?.get(SimplifiedBooks.id)?.value
    }

    // 에러 관리
    fun reportErrorToAdmin(errorItemIds: Set<Int>) {
        // 이메일로 에러 리포트 보내기
        sendErrorReportEmail(errorItemIds)

        // 로그 파일에 에러 리포트 기록하기
        logErrorReportToFile(errorItemIds)

        // 알림 시스템에 에러 리포트 보내기
        sendErrorReportNotification(errorItemIds)
    }

    fun sendErrorReportEmail(errorItemIds: Set<Int>) {
        // 이메일로 에러 리포트 보내는 로직 구현
        println("Sending email: Error ItemIds - $errorItemIds")
    }

    fun logErrorReportToFile(errorItemIds: Set<Int>) {
        // 로그 파일에 에러 리포트 기록하는 로직 구현
        println("Logging to file: Error ItemIds - $errorItemIds")
    }

    fun sendErrorReportNotification(errorItemIds: Set<Int>) {
        // 알림 시스템에 에러 리포트 보내는 로직 구현
        println("Sending notification: Error ItemIds - $errorItemIds")
    }

    companion object {
        const val ERROR_THRESHOLD = 10
    }

    // User Select
    fun getBooksByBirth(birth: Int): List<ResultRow> {
        // 세 개의 테이블에 모두 해당하는 레코드만
        return (SimplifiedBooks innerJoin HitsRecords innerJoin Users)
                .select { Users.birth eq birth }
                .groupBy(SimplifiedBooks.itemId, SimplifiedBooks.publisher, SimplifiedBooks.title, SimplifiedBooks.author, SimplifiedBooks.categoryName)
                .orderBy(HitsRecords.hitsCount to SortOrder.DESC)
                .map { it }
    }
    fun getBooksByGender(gender: Int): List<ResultRow> {
        // 세 개의 테이블에 모두 해당하는 레코드만
        return (SimplifiedBooks innerJoin HitsRecords innerJoin Users)
                .select { Users.gender eq gender }
                .groupBy(SimplifiedBooks.itemId, SimplifiedBooks.publisher, SimplifiedBooks.title, SimplifiedBooks.author, SimplifiedBooks.categoryName)
                .orderBy(HitsRecords.hitsCount to SortOrder.DESC)
                .map { it }
    }


}
