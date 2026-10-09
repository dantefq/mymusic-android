package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class AudiobooksTest {
    @Test fun realTransliteratedChaptersNeverReplaceTheRussianFolderName() {
        val book = groupAudiobooks((1..43).map { number -> chapter(number.toLong(), "%02d - lun - glava%03dm.mp3".format(number, number),
            "Audiobooks/Лунь/", "Audiobooks").copy(artist = "Unknown") }).single()
        assertEquals("Лунь", book.title)
        assertEquals("", book.author)
        assertEquals((1L..43L).toList(), book.chapters.map { it.id })
        assertEquals(ChapterNumbers(25, null), chapterNumbers(book.chapters[24]))
    }

    @Test fun chapterOnlyAlbumTagsCannotRenameABook() {
        val book = groupAudiobooks(listOf(chapter(1, "01 - lun - glava001m.mp3", "Audiobooks/Лунь/", "glava001m"))).single()
        assertEquals("Лунь", book.title)
    }

    @Test fun staleOnlineMetadataCannotOverrideACorrectedLocalTitle() {
        val book = groupAudiobooks(listOf(chapter(1, "01.mp3", "Audiobooks/Лунь/"))).single()
        assertNull(matchingBookDetails(book, BookDetails("glava001m", "lun", "", null, "", "", 0)))
    }

    @Test fun authorAndTitleAreRecoveredFromFolderAndFilename() {
        val fromFolder = groupAudiobooks(listOf(chapter(1, "01.mp3", "Audiobooks/Михаил Булгаков/Мастер и Маргарита/").copy(artist = "Unknown"))).single()
        assertEquals("Михаил Булгаков", fromFolder.author)
        assertEquals("Мастер и Маргарита", fromFolder.title)
        val fromName = groupAudiobooks(listOf(chapter(1, "Михаил Булгаков - Мастер и Маргарита - Глава 01.mp3", "Audiobooks/").copy(artist = "Unknown"))).single()
        assertEquals("Михаил Булгаков", fromName.author)
        assertEquals("Мастер и Маргарита", fromName.title)
    }

    @Test fun placeholderAuthorsDoNotMaskUsefulTags() {
        val book = groupAudiobooks(listOf(chapter(1, "01.mp3").copy(albumArtist = "<unknown>"))).single()
        assertEquals("Михаил Булгаков", book.author)
        assertNull(bookAuthorValue("Неизвестный автор"))
        assertNull(bookAuthorValue("Глава 1"))
    }

    @Test fun numericBookTitlesKeepTheirOwnFolder() {
        val books = groupAudiobooks(listOf(chapter(1, "01.mp3", "Audiobooks/1984/"), chapter(2, "01.mp3", "Audiobooks/451/")))
        assertEquals(2, books.size)
        assertEquals(setOf("1984", "451"), books.map { it.title }.toSet())
    }

    @Test fun remainingTimeUsesTheSavedChapterAndClampsPosition() {
        val book = groupAudiobooks(listOf(chapter(1, "01.mp3"), chapter(2, "02.mp3"))).single()
        assertEquals(200000L, bookRemainingMs(book, null))
        assertEquals(63000L, bookRemainingMs(book, BookBookmark(2, 37000, 1)))
        assertEquals(100000L, bookRemainingMs(book, BookBookmark(1, 500000, 1)))
        assertEquals(0L, bookRemainingMs(book, BookBookmark(2, 100000, 1, true)))
        assertEquals(200000L, bookRemainingMs(book, BookBookmark(99, 37000, 1)))
    }

    @Test fun listenedMarksDoNotTreatSkippedChaptersAsCompleted() {
        val book = groupAudiobooks(listOf(chapter(1, "01.mp3"), chapter(2, "02.mp3"))).single()
        val saved = BookBookmark(2, 37000, 1)
        assertFalse(chapterCompleted(book, saved, 1))
        assertTrue(chapterCompleted(book, saved.copy(completedChapters = setOf(1)), 1))
        assertTrue(chapterCompleted(book, saved.copy(completed = true), 1))
        assertFalse(chapterCompleted(book, saved.copy(completed = true, completedChapters = setOf(2)), 1))
        assertFalse(chapterCompleted(book, saved.copy(completed = true), 99))
    }

    private fun chapter(id: Long, file: String, folder: String = "Audiobooks/Мастер и Маргарита/", album: String = "") =
        Track(id, "file:$id", file, "Михаил Булгаков", album, 100000, "audio/mpeg", isAudiobook = true,
            folderPath = folder, fileName = file)

    @Test fun foldersSeparateBooksEvenWhenAlbumTagsAreIdentical() {
        val books = groupAudiobooks(listOf(chapter(1, "01.mp3", album = "Unknown album"),
            chapter(2, "02.mp3", album = "Unknown album"), chapter(3, "01.mp3", "Аудиокниги/Собачье сердце/", "Unknown album")))
        assertEquals(2, books.size)
        assertEquals(listOf(1L, 2L), books.first { it.title == "Мастер и Маргарита" }.chapters.map { it.id })
    }

    @Test fun chaptersAreSortedNumericallyWithoutLeadingZeros() {
        val book = groupAudiobooks(listOf(chapter(10, "10.mp3"), chapter(2, "2.mp3"), chapter(1, "01.mp3"))).single()
        assertEquals(listOf(1L, 2L, 10L), book.chapters.map { it.id })
    }

    @Test fun multipartChapterNumbersAreRecognized() {
        assertEquals(ChapterNumbers(2, 3), chapterNumbers(chapter(1, "02_03.mp3")))
        assertEquals(ChapterNumbers(4, 2), chapterNumbers(chapter(1, "Глава 04 Часть 02.mp3")))
        assertEquals(ChapterNumbers(4, 2), chapterNumbers(chapter(1, "Chapter 04 Part 02.mp3")))
        val book = groupAudiobooks(listOf(chapter(2, "01_02.mp3"), chapter(3, "02_01.mp3"), chapter(1, "01_01.mp3"))).single()
        assertEquals(listOf(1L, 2L, 3L), book.chapters.map { it.id })
    }

    @Test fun discSubfoldersStayInOneBookAndSortNaturally() {
        val book = groupAudiobooks(listOf(chapter(3, "01.mp3", "Audiobooks/Book/CD10/"),
            chapter(2, "01.mp3", "Audiobooks/Book/CD2/"), chapter(1, "01.mp3", "Audiobooks/Book/CD1/"))).single()
        assertEquals("Book", book.title)
        assertEquals(listOf(1L, 2L, 3L), book.chapters.map { it.id })
        assertEquals(ChapterNumbers(1, 10), chapterNumbers(book.chapters.last()))
    }

    @Test fun genericRootUsesAlbumAndAuthorToSeparateBooks() {
        val books = groupAudiobooks(listOf(chapter(1, "01.mp3", "Audiobooks/", "Book One"),
            chapter(2, "01.mp3", "Audiobooks/", "Book Two")))
        assertEquals(2, books.size)
    }

    @Test fun filenameFallbackGroupsPartsInTheRoot() {
        val books = groupAudiobooks(listOf(chapter(1, "Book One_01.mp3", "Audiobooks/"),
            chapter(2, "Book One_02.mp3", "Audiobooks/"), chapter(3, "Book Two_01.mp3", "Audiobooks/")))
        assertEquals(2, books.size)
        assertEquals(2, books.first { it.title == "Book One" }.chapters.size)
    }

    @Test fun russianAndEnglishFoldersAreDetected() {
        assertTrue(isAudiobookPath("Music/Аудиокниги/Book/"))
        assertTrue(isAudiobookPath("Audiobooks/Book/"))
        assertFalse(isAudiobookPath("Music/Albums/"))
    }

    @Test fun musicIsExcludedFromBookshelf() {
        assertTrue(groupAudiobooks(listOf(chapter(1, "song.mp3").copy(isAudiobook = false))).isEmpty())
    }

    @Test fun bookmarksAreIndependentForEachBook() {
        val first = groupAudiobooks(listOf(chapter(1, "01.mp3"), chapter(2, "02.mp3"))).single()
        val second = groupAudiobooks(listOf(chapter(3, "01.mp3", "Audiobooks/Other/"))).single()
        val saved = mapOf(first.key to BookBookmark(2, 42000, 1), second.key to BookBookmark(3, 17000, 2))
        assertEquals(BookResume(first.chapters[1], 42000), bookResume(first, saved[first.key]))
        assertEquals(BookResume(second.chapters[0], 17000), bookResume(second, saved[second.key]))
        assertEquals(.71f, bookProgress(first, saved[first.key]), .001f)
    }

    @Test fun removedChapterAndCompletedBookRestartSafely() {
        val book = groupAudiobooks(listOf(chapter(1, "01.mp3"))).single()
        assertEquals(BookResume(book.chapters.first(), 0), bookResume(book, BookBookmark(5, 50000, 1)))
        assertEquals(BookResume(book.chapters.first(), 0), bookResume(book, BookBookmark(1, 100000, 1, true)))
        assertEquals(1f, bookProgress(book, BookBookmark(1, 100000, 1, true)), 0f)
        assertEquals(99999L, bookResume(book, BookBookmark(1, 500000, 1)).positionMs)
    }

    @Test fun metadataMustMatchTitleAndKnownAuthor() {
        assertTrue(matchesBook("Мастер и Маргарита", "Михаил Булгаков", "Мастер и Маргарита", "М. Булгаков"))
        assertFalse(matchesBook("Мастер и Маргарита", "Михаил Булгаков", "Собачье сердце", "Михаил Булгаков"))
        assertFalse(matchesBook("The Stand", "Stephen King", "The Stand", "Another Writer"))
        assertTrue(matchesBook("The Stand", "", "The Stand", "Stephen King"))
    }

    @Test fun punctuationDoesNotTruncateNamesAndLongNumbersDoNotOverflow() {
        assertEquals("А. С. Пушкин", cleanBookText("А. С. Пушкин"))
        assertTrue(naturalCompare("part_99999999999999999999", "part_100000000000000000000") < 0)
        assertEquals(ChapterNumbers(1, 2), chapterNumbers(chapter(1, "01.02.mp3")))
    }
}
