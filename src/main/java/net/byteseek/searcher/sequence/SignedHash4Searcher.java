/*
 * Copyright Matt Palmer 2017, All rights reserved.
 *
 * This code is licensed under a standard 3-clause BSD license:
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 *  * The names of its contributors may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.byteseek.searcher.sequence;

import net.byteseek.io.reader.WindowReader;
import net.byteseek.io.reader.windows.Window;
import net.byteseek.matcher.sequence.ByteSequenceMatcher;
import net.byteseek.matcher.sequence.SequenceMatcher;
import net.byteseek.utils.ArgUtils;
import net.byteseek.utils.ByteUtils;
import net.byteseek.utils.collections.BytePermutationIterator;
import net.byteseek.utils.factory.ObjectFactory;
import net.byteseek.utils.lazy.DoubleCheckImmutableLazyObject;
import net.byteseek.utils.lazy.LazyObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * An implementation of the SignedHash algorithm by Matt Palmer.
 * <p>
 * This is essentially the Signed Wu-Manber algorithm applied to a single pattern instead of multiple patterns.  It uses
 * a hash of a q-gram to look up a safe shift.  Since q-grams appear less frequently than single bytes,
 * the corresponding shifts can be bigger, for longer or more complex patterns.
 * The bigger shifts then offset the additional cost of reading more bytes for the q-gram and
 * calculating its hash, leading to better performance.
 * <p>
 * The algorithm was inspired by Lecroq's adaptation of Wu-Manber for single pattern searching described in
 * "Fast Exact String Matching Algorithms".  However there are some significant differences - it doesn't require
 * a sentinel pattern at the end, it handles byte classes, it uses the signed-searching technique, and it uses
 * multiply-shift hashing which allows a greater choice of hash table sizes.
 * <p>
 * The core algorithm permits q-grams of different lengths to be used.  This implementation uses a q-gram of length 4.
 * Note that if a pattern shorter than the qgram length is passed in, this algorithm cannot search for it,
 * and a different algorithm (ShiftOr) will be substituted, which is generally fastest for short patterns.
 * ShiftOr creates a table of 256 elements, which in most cases will be the same or smaller
 * than the table used by this searcher, and whose pre-processing time is usually faster.
 *
 * Created by Matt Palmer on 06/05/17.
 */
public final class SignedHash4Searcher extends AbstractSequenceWindowSearcher<SequenceMatcher> {

    /*************
     * Constants *
     *************/

    /**
     * The length of q-gram processed by this searcher.
     */
    private final static int QLEN = 4;

    /**
     * The hash table size used by the hash function, expressed as a power of two.
     * A zero power of two tells the algorithm to select the smallest table which will give good performance.
     * A negative power of two means the algorithm limits the biggest table it will select to the (positive) power of two.
     * If a required table is bigger than the maximum size, we revert to the replacement searcher (ShiftOr).
     */
    private final static int DEFAULT_POWER_TWO = -16; // automatically select a hash table size no larger than 2^16 = 65k.

    /**
     * The minimum size of the hash table automatically selected by this algorithm, expressed as a power of two.
     */
    private final static int MIN_POWER_TWO_SIZE = 5; // equals 2^4 = 32.

    /**
     * The maximum size of the hash table supported by the algorithm, expressed as a power of two.
     */
    private final static int MAX_POWER_TWO_SIZE = 28;

    //TODO: validate the restrictions on this value.  Unless the constant is around 64 bits, small values all end up
    //      mapping to zero, so adjacent keys get the same hash.  Concerning because I haven't seen discussion of this
    //      property about the hash function.  Small values end up as zero because the shift puts the higher bits into the
    //      lower bits.  Unless the higher bits get populated during the multiply stage (overflow isn't a problem),
    //      then all the lower bits are zero.
    /**
     * A constant used in the multiply-shift hash algorithm.  The qgram (as an integer) is multiplied by this
     * value, which must be odd, and around 64 bits in length. Other than those restrictions, it's just a random value.
     */
    private final static long HASH_MULTIPLY = 0xee4c2ad3f592b105L;


    /**********
     * Fields *
     **********/

    /**
     * The size of the hash table as a power of two.  Can also be zero (auto select a good size),
     * and negative (auto select, but don't exceed the maximum of the (positive) power of two).
     */
    private final int POWER_TWO_SIZE;

    /**
     * A lazy object which can create the information needed to search.
     * An array of shifts is used to determine how far it is safe to shift given a qgram seen in the text.
     */
    private final LazyObject<SearchInfo> forwardSearchInfo;
    private final LazyObject<SearchInfo> backwardSearchInfo;

    /**
     * A replacement searcher for sequences whose length is less than the qgram length, which this searcher cannot search for.
     * Also used as a fallback in case it is not possible to create a hash table which would give reasonable performance
     * (e.g. if the maximum table size isn't sufficient, or the pattern is pathological in some way).
     */
    private final LazyObject<SequenceSearcher<SequenceMatcher>> fallbackSearcher;


    /****************
     * Constructors *
     ****************/

    /**
     * Constructs a searcher given a {@link SequenceMatcher} to search for.
     *
     * @param sequence The SequenceMatcher to search for.
     */
    public SignedHash4Searcher(final SequenceMatcher sequence) {
        this(sequence, DEFAULT_POWER_TWO);
    }

    /**
     * Constructs a searcher given a {@link SequenceMatcher} to search for, and a powerTwoSize which determines
     * the size of the hash table used, up to maximum of 31, = 2^31, the largest possible array in Java.
     * <p>
     * If the powerTwoSize is set to zero, then the smallest table size which could give good performance for the
     * pattern will be automatically selected, although it may result in very large table sizes for some complex
     * patterns.
     * <p>
     * If the powerTwoSize is negative, then the smallest good performing table size will also be automatically
     * selected, up to a maximum size given by the positive value, e.g. -12 = 2^12 = no bigger than 4096 elements.
     * If the pattern is too complex to be adequately represented by the available table size,
     * a replacement searcher will be used in place of this algorithm (which is ShiftOR).  This is because if the
     * hash table is too small, the available shifts will be very small too and searching will consequently be
     * very slow.  While ShiftOR isn't particularly fast, it is faster than using this algorithm poorly and
     * does not suffer at all from complexity in the patterns.
     *
     * @param sequence      The SequenceMatcher to search for.
     * @param powerTwoSize  Determines the size of the hash table used by the search algorithm.
     * @throws IllegalArgumentException if the sequence is null or empty or the shift is less than -28 or greater than 28.
     */
    public SignedHash4Searcher(final SequenceMatcher sequence, final int powerTwoSize) {
        super(sequence);
        ArgUtils.checkRangeInclusive(powerTwoSize, -MAX_POWER_TWO_SIZE, MAX_POWER_TWO_SIZE, "powerTwoSize");
        POWER_TWO_SIZE = powerTwoSize;
        if (sequence.length() >= QLEN) { // equal or bigger to qgram length - searchable by this algorithm
            forwardSearchInfo  = new DoubleCheckImmutableLazyObject<SearchInfo>(new ForwardSearchInfoFactory());
            backwardSearchInfo = new DoubleCheckImmutableLazyObject<SearchInfo>(new BackwardSearchInfoFactory());
        } else {                         // smaller than a qgram length - use a different searcher.
            forwardSearchInfo  = null;
            backwardSearchInfo = null;
        }
        fallbackSearcher = new DoubleCheckImmutableLazyObject<SequenceSearcher<SequenceMatcher>>(new ShortSearcherFactory());
    }

    /**
     * Constructs a searcher for the bytes contained in the sequence string,
     * encoded using the platform default character set.
     *
     * @param sequence The string to search for.
     * @throws IllegalArgumentException if the sequence is null or empty.
     */
    public SignedHash4Searcher(final String sequence) {
        this(sequence, Charset.defaultCharset(), DEFAULT_POWER_TWO);
    }

    /**
     * Constructs a searcher for the bytes contained in the sequence string,
     * encoded using the platform default character set, and a powerTwoSize which determines
     * the size of the hash table used, up to maximum of 31, = 2^31, the largest possible array in Java.
     * <p>
     * If the powerTwoSize is set to zero, then the smallest table size which could give good performance for the
     * pattern will be automatically selected, although it may result in very large table sizes for some complex
     * patterns.
     * <p>
     * If the powerTwoSize is negative, then the smallest good performing table size will also be automatically
     * selected, up to a maximum size given by the positive value, e.g. -12 = 2^12 = no bigger than 4096 elements.
     * If the pattern is too complex to be adequately represented by the available table size,
     * a replacement searcher will be used in place of this algorithm (which is ShiftOR).  This is because if the
     * hash table is too small, the available shifts will be very small too and searching will consequently be
     * very slow.  While ShiftOR isn't particularly fast, it is faster than using this algorithm poorly and
     * does not suffer at all from complexity in the patterns.
     *
     * @param sequence The string to search for.
     * @param powerTwoSize  Determines the size of the hash table used by the search algorithm.
     * @throws IllegalArgumentException if the sequence is null or empty or the powerTwoSize is less than -28 or greater than 28.
     */
    public SignedHash4Searcher(final String sequence, final int powerTwoSize) {
        this(sequence, Charset.defaultCharset(), powerTwoSize);
    }

    /**
     * Constructs a searcher for the bytes contained in the sequence string,
     * encoded using the charset provided.
     *
     * @param sequence The string to search for.
     * @param charset The charset to encode the string in.
     * @throws IllegalArgumentException if the sequence is null or empty, or the charset is null.
     */
    public SignedHash4Searcher(final String sequence, final Charset charset) {
        this(sequence == null? null : charset == null? null : new ByteSequenceMatcher(sequence.getBytes(charset)));
    }

    /**
     * Constructs a searcher for the bytes contained in the sequence string,
     * encoded using the charset provided, and a powerTwoSize which determines
     * the size of the hash table used, up to maximum of 31, = 2^31, the largest possible array in Java.
     * <p>
     * If the powerTwoSize is set to zero, then the smallest table size which could give good performance for the
     * pattern will be automatically selected, although it may result in very large table sizes for some complex
     * patterns.
     * <p>
     * If the powerTwoSize is negative, then the smallest good performing table size will also be automatically
     * selected, up to a maximum size given by the positive value, e.g. -12 = 2^12 = no bigger than 4096 elements.
     * If the pattern is too complex to be adequately represented by the available table size,
     * a replacement searcher will be used in place of this algorithm (which is ShiftOR).  This is because if the
     * hash table is too small, the available shifts will be very small too and searching will consequently be
     * very slow.  While ShiftOR isn't particularly fast, it is faster than using this algorithm poorly and
     * does not suffer at all from complexity in the patterns.
     *
     * @param sequence The string to search for.
     * @param charset The charset to encode the string in.
     * @param powerTwoSize  Determines the size of the hash table used by the search algorithm.
     * @throws IllegalArgumentException if the sequence is null or empty, or the charset is null.
     */
    public SignedHash4Searcher(final String sequence, final Charset charset, final int powerTwoSize) {
        this(sequence == null? null : charset == null? null : new ByteSequenceMatcher(sequence.getBytes(charset)), powerTwoSize);
    }

    /**
     * Constructs a searcher for the byte array provided.
     *
     * @param sequence The byte sequence to search for.
     * @throws IllegalArgumentException if the sequence is null or empty.
     */
    public SignedHash4Searcher(final byte[] sequence) {
        this(sequence == null? null : new ByteSequenceMatcher(sequence), DEFAULT_POWER_TWO);
    }

    /**
     * Constructs a searcher for the byte array provided and a powerTwoSize which determines
     * the size of the hash table used, up to maximum of 31, = 2^31, the largest possible array in Java.
     * <p>
     * If the powerTwoSize is set to zero, then the smallest table size which could give good performance for the
     * pattern will be automatically selected, although it may result in very large table sizes for some complex
     * patterns.
     * <p>
     * If the powerTwoSize is negative, then the smallest good performing table size will also be automatically
     * selected, up to a maximum size given by the positive value, e.g. -12 = 2^12 = no bigger than 4096 elements.
     * If the pattern is too complex to be adequately represented by the available table size,
     * a replacement searcher will be used in place of this algorithm (which is ShiftOR).  This is because if the
     * hash table is too small, the available shifts will be very small too and searching will consequently be
     * very slow.  While ShiftOR isn't particularly fast, it is faster than using this algorithm poorly and
     * does not suffer at all from complexity in the patterns.
     *
     * @param sequence The byte sequence to search for.
     * @param powerTwoSize Determines the size of the hash table used by the search algorithm.
     * @throws IllegalArgumentException if the sequence is null or empty, or the charset is null.
     */
    public SignedHash4Searcher(final byte[] sequence, final int powerTwoSize) {
        this(sequence == null? null : new ByteSequenceMatcher(sequence), powerTwoSize);
    }


    /******************
     * Search Methods *
     ******************/

    @Override
    public int searchSequenceForwards(final byte[] bytes, final int fromPosition, final int toPosition) {

        // Get the pre-processed data needed to search:
        final SearchInfo searchInfo = forwardSearchInfo.get();
        final int[] SHIFTS          = searchInfo.shifts;
        final int HASH_SHIFT        = searchInfo.bitshift;

        // Do we need to use the fallback searcher (no shifts available)?
        if (SHIFTS == null) {
            return fallbackSearcher.get().searchSequenceForwards(bytes, fromPosition, toPosition);
        }

        // Get local copies of member fields
        final SequenceMatcher localSequence = sequence;

        // Determine safe shifts, starts and ends:
        final int LAST_PATTERN_POS     = localSequence.length() - 1;
        final int DATA_END_POS         = bytes.length - 1;
        final int LAST_SEARCH_POS      = toPosition + LAST_PATTERN_POS;
        final int SEARCH_END           = LAST_SEARCH_POS < DATA_END_POS?
                                         LAST_SEARCH_POS : DATA_END_POS;
        final int SEARCH_START         = fromPosition > 0?
                                         fromPosition : 0;

        // Search forwards:
        int searchPos = SEARCH_START + LAST_PATTERN_POS; // look at the end of the pattern to determine shift.
        while (searchPos <= SEARCH_END) {

            // Calculate hash of qgram:
            final int hash = (int) (((((((bytes[searchPos - 3] & 0xFF) << 8) | // build qgram (integer from bytes)
                                         (bytes[searchPos - 2] & 0xFF) << 8) |
                                         (bytes[searchPos - 1] & 0xFF) << 8) |
                                         (bytes[searchPos    ] & 0xFF))
                                    * HASH_MULTIPLY) >>> HASH_SHIFT);          // multiply-shift hash.

            // Get the shift for this qgram:
            final int shift = SHIFTS[hash];

            // If we have a positive shift:
            if (shift > 0) {
                searchPos += shift; // just shift forwards - no match here.
            } else {                // A negative shift means the last qgram may match.
                final int matchPos = searchPos - LAST_PATTERN_POS;
                if (localSequence.matchesNoBoundsCheck(bytes, matchPos)) { // validate whether we have a match.
                    return matchPos;
                }
                searchPos -= shift; // shift forwards by subtracting the negative shift.
            }
        }
        return NO_MATCH;
    }

    @Override
    public long searchSequenceForwards(final WindowReader reader, final long fromPosition, final long toPosition) throws IOException {
        return forwardSearchInfo.get().shifts != null?
                  super.searchSequenceForwards(reader, fromPosition, toPosition)
                : fallbackSearcher.get().searchSequenceForwards(reader, fromPosition, toPosition);
    }

    @Override
    protected long doSearchForwards(final WindowReader reader, final long fromPosition, final long toPosition) throws IOException {

        // Get the pre-processed data needed to search:
        final SearchInfo searchInfo = forwardSearchInfo.get();
        final int[] SHIFTS          = searchInfo.shifts;
        final int   HASH_SHIFT      = searchInfo.bitshift;

        // Get local copies of member fields
        final SequenceMatcher localSequence = sequence;

        // Determine safe shifts, starts and ends:
        final int LAST_PATTERN_POS = localSequence.length() - 1;
        final int LAST_QGRAM_POS   = QLEN - 1;
        final long SEARCH_END      = toPosition + LAST_PATTERN_POS;
        long searchPos             = (fromPosition > 0?
                                      fromPosition : 0) + LAST_PATTERN_POS;
        // Search forwards:
        Window window;
        while (searchPos <= SEARCH_END && (window = reader.getWindow(searchPos)) != null) {

            // Get window array info:
            final byte[] array       = window.getArray();
            final int    arrayEndPos = window.length() - 1;
            int          arrayPos    = reader.getWindowOffset(searchPos);

            // Calculate array search end positions:
            final long DISTANCE_TO_END   = SEARCH_END - searchPos;
            final int REMAINING_IN_ARRAY = arrayEndPos - arrayPos;
            final int LAST_ARRAY_POS     = DISTANCE_TO_END < REMAINING_IN_ARRAY?
                                     (int) DISTANCE_TO_END + arrayPos : arrayEndPos;

            // Search forwards if there is still anything to search in this array:
            while (arrayPos <= LAST_ARRAY_POS) {

                // Calculate hash:
                final int hash = arrayPos < LAST_QGRAM_POS?
                    (int) (((((((reader.readByte(searchPos - 3)) << 8) | // build qgram...
                                (reader.readByte(searchPos - 2)) << 8) |
                                (reader.readByte(searchPos - 1)) << 8) |
                                (array[arrayPos] & 0xFF))
                           * HASH_MULTIPLY) >>> HASH_SHIFT)             // multiply shift hash.
                  : (int) (((((((array[arrayPos - 3] & 0xFF) << 8) | // build qgram
                                (array[arrayPos - 2] & 0xFF) << 8) |
                                (array[arrayPos - 1] & 0xFF) << 8) |
                                (array[arrayPos    ] & 0xFF))
                           * HASH_MULTIPLY) >>> HASH_SHIFT);        // multiply shift hash.

                // Get shift and either shift forwards, or verify then shift
                final int shift = SHIFTS[hash];
                if (shift > 0) {
                    arrayPos  += shift;
                    searchPos += shift;
                } else {
                    final long matchPos = searchPos - LAST_PATTERN_POS;
                    if (localSequence.matches(reader, matchPos)) {
                        return matchPos;
                    }
                    arrayPos  -= shift;
                    searchPos -= shift;
                }
            }
        }
        return NO_MATCH;
    }

    @Override
    public int searchSequenceBackwards(byte[] bytes, int fromPosition, int toPosition) {

        // Get the pre-processed data needed to search:
        final SearchInfo searchInfo = backwardSearchInfo.get();
        final int[] SHIFTS          = searchInfo.shifts;
        final int   HASH_SHIFT      = searchInfo.bitshift;

        // Do we need to use the fallback searcher (no shifts available)?
        if (SHIFTS == null) {
            return fallbackSearcher.get().searchSequenceBackwards(bytes, fromPosition, toPosition);
        }

        // Get local copies of member fields
        final SequenceMatcher localSequence = sequence;

        // Determine safe shifts, starts and ends:
        final int LAST_MATCH_POS = bytes.length - localSequence.length();
        final int SEARCH_START   = fromPosition < LAST_MATCH_POS?
                                   fromPosition : LAST_MATCH_POS;
        final int SEARCH_END     = toPosition > 0?
                                   toPosition : 0;

        // Search backwards:
        int searchPos = SEARCH_START;
        while (searchPos >= SEARCH_END) {

            // Calculate hash of qgram:
            final int hash = (int) (((((((bytes[searchPos + 3] & 0xFF) << 8) | // build qgram
                                         (bytes[searchPos + 2] & 0xFF) << 8) |
                                         (bytes[searchPos + 1] & 0xFF) << 8) |
                                         (bytes[searchPos    ] & 0xFF))
                                     * HASH_MULTIPLY) >>> HASH_SHIFT);         // multiply shift hash.

            // Get the shift for this qgram:
            final int shift = SHIFTS[hash];
            if (shift > 0) {        // If we have a positive shift,
                searchPos -= shift; // just shift backwards - no match here.
            } else {                // A negative shift means the last qgram may match.
                if (localSequence.matchesNoBoundsCheck(bytes, searchPos)) { // validate whether we have a match.
                    return searchPos;
                }
                searchPos += shift; // shift backwards by adding the negative shift.
            }
        }
        return NO_MATCH;
    }

    @Override
    public long searchSequenceBackwards(final WindowReader reader, final long fromPosition, final long toPosition) throws IOException {
        return backwardSearchInfo.get().shifts != null?
                  super.searchSequenceBackwards(reader, fromPosition, toPosition)
                : fallbackSearcher.get().searchSequenceBackwards(reader, fromPosition, toPosition);
    }

    @Override
    protected long doSearchBackwards(final WindowReader reader, final long fromPosition, final long toPosition) throws IOException {
        // Get local copies of member fields
        final SequenceMatcher localSequence = sequence;

        // Get the pre-processed data needed to search:
        final SearchInfo searchInfo = backwardSearchInfo.get();
        final int[] SHIFTS          = searchInfo.shifts;
        final int HASH_SHIFT        = searchInfo.bitshift;

        // Determine safe shifts, starts and ends:
        final long SEARCH_START = fromPosition; // TODO: withinLength?  needed for doSearchBackwards?
        final long SEARCH_END   = toPosition > 0?
                                  toPosition : 0;

        // Search forwards:
        Window window;
        long searchPos = SEARCH_START;
        while (searchPos >= SEARCH_END && (window = reader.getWindow(searchPos)) != null) {

            // Get window array info:
            final byte[] array               = window.getArray();
            final int    WINDOW_LENGTH       = window.length();

            // Calculate safe starts and ends:
            final int    CROSSOVER_QGRAM_POS = WINDOW_LENGTH - QLEN + 1;
            final long DISTANCE_TO_END       = SEARCH_END - window.getWindowPosition();
            final int  LAST_ARRAY_POS        = DISTANCE_TO_END > 0?
                                         (int) DISTANCE_TO_END : 0;

            // Search backwards if there is still anything to search in this array:
            int arrayPos = reader.getWindowOffset(searchPos);
            while (arrayPos >= LAST_ARRAY_POS) {

                //TODO: can this read past reader end?  what if next window doesn't exist...?
                //      probably can, but may not matter.  If no room for another qgram,
                //      then can get no more matches at current search pos.  hash may be wrong, but we'll
                //      move past the end on the next iteration or two...

                // Calculate hash:
                final int hash = arrayPos >= CROSSOVER_QGRAM_POS?  // crosses over into next window?
                    (int) (((((((reader.readByte(searchPos + 3)) << 8) | // build qgram
                                (reader.readByte(searchPos + 2)) << 8) |
                                (reader.readByte(searchPos + 1)) << 8) |
                                (array[arrayPos] & 0xFF))
                           * HASH_MULTIPLY) >>> HASH_SHIFT)              // multiply shift hash.
                  : (int) (((((((array[arrayPos + 3] & 0xFF) << 8) |  // build qgram...
                                (array[arrayPos + 2] & 0xFF) << 8) |
                                (array[arrayPos + 1] & 0xFF) << 8) |
                                (array[arrayPos    ] & 0xFF))
                           * HASH_MULTIPLY) >>> HASH_SHIFT);          // multiply shift hash.

                // Get shift and either shift forwards, or verify then shift
                final int shift = SHIFTS[hash];
                if (shift > 0) {
                    arrayPos  -= shift;
                    searchPos -= shift;
                } else {
                    if (localSequence.matches(reader, searchPos)) {
                        return searchPos;
                    }
                    arrayPos  += shift;
                    searchPos += shift;
                }
            }
        }
        return NO_MATCH;
    }


    /******************
     * Public methods *
     ******************/

    @Override
    public void prepareForwards() {
        forwardSearchInfo.get();
    }

    @Override
    public void prepareBackwards() {
        backwardSearchInfo.get();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[powerTwoSize:" + POWER_TWO_SIZE + " sequence:" + sequence + ']';
    }


    /*********************
     * Protected methods *
     *********************/

    @Override
    protected int getSequenceLength() {
        return sequence.length();
    }


    /*******************
     * Private classes *
     *******************/

    /**
     * A factory for a short sequence searcher, to fill in for sequences with a length less than the Qgram length.
     * <p>
     * <b>Design Note</b>
     * <p>
     * This allows a developer to pass any valid pattern into this search algorithm without error.  The alternative is
     * to throw an IllegalArgumentException in the constructor if the pattern is too short, but this could easily
     * lead to errors in user applications, since patterns are commonly supplied by the user, not the programmer.
     * <p>
     * While this decision violates the principle that these search algorithms are primitives and should not make high
     * level decisions on behalf of the programmer, it seems the lesser of two evils to make it safe to use any search
     * algorithm with any valid pattern, even if occasionally you don't quite get the algorithm you thought you specified.
     * <p>
     * Given this, we choose to supply the fastest known algorithm for short patterns (ShiftOr),
     * rather than one which is more spiritually similar to this algorithm (e.g. the SignedHorspoolSearcher), or the
     * simplest possible algorithm (e.g. the SequenceMatcherSearcher).
     */
    private final class ShortSearcherFactory implements ObjectFactory<SequenceSearcher<SequenceMatcher>> {

        @Override
        public SequenceSearcher<SequenceMatcher> create() {
            return new ShiftOrSearcher(sequence);  // the fastest searcher for short patterns.
        }
    }


    /**
     * A simple data class containing the shifts for searching and the bitshift needed for the hash-multiply hash function.
     */
    private final static class SearchInfo {
        public final int[] shifts;
        public final int bitshift;
        public SearchInfo(final int[] shifts, final int bitshift) {
            this.shifts   = shifts;
            this.bitshift = bitshift;
        }
    }

    private final static SearchInfo NULL_SEARCH_INFO = new SearchInfo(null, 0);


    /**
     * A factory for the shift table and hash bitshift needed to search forwards.
     */
    private final class ForwardSearchInfoFactory implements ObjectFactory<SearchInfo> {

        /**
         * Calculates the shift table for forwards searching.
         */
        @Override
        public SearchInfo create() {
            // Get local copies of fields:
            final SequenceMatcher localSequence = sequence;

            // If the pattern is shorter than one qgram, the fallback searcher will be used instead.
            final int PATTERN_LENGTH = localSequence.length();
            if (PATTERN_LENGTH < QLEN) {
                return NULL_SEARCH_INFO; // no shifts to calculate.
            }

            // Determine the maximum size of the hash table:
            final int MAX_HASH_POWER_TWO_SIZE = (POWER_TWO_SIZE > 0)? POWER_TWO_SIZE
                                                                    : POWER_TWO_SIZE == 0? MAX_POWER_TWO_SIZE
                                                                                         : -POWER_TWO_SIZE;

            // Calculate how many qgrams we have, but stop if we get to more than we can handle with good performance.
            // This will give us the size of the hash table and the starting position of the qgram to calculate shifts for,
            // and the maximum shift we can make.  Normally this will be simply be the length of the pattern, but if more qgrams exist than can be
            // reasonably processed, the useful qgram start could be further along the pattern, so the max shift will be shorter.
            final int MAX_TABLE_SIZE = 1 << MAX_HASH_POWER_TWO_SIZE;
            int num0;
            int num1 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 1);
            int num2 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 2);
            int num3 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 3);
            int totalQgrams = 0;
            int qGramStartPos;
            for (qGramStartPos = PATTERN_LENGTH - QLEN; qGramStartPos >= 0; qGramStartPos--) {
                num0 = num1; num1 = num2; num2 = num3; // shift byte counts along.
                num3 = localSequence.getNumBytesAtPosition(qGramStartPos); // get next count.
                totalQgrams += (num0 * num1 * num2 * num3);
                // If we go beyond the max table load, stop further processing.
                if ((totalQgrams >> 2) >= MAX_TABLE_SIZE) { // if there's four times as many qgrams as max table size (quarter bigger than max):
                    qGramStartPos--; // make the sums add up, will be re-added when loop ends.
                    break; // no further value, halt processing of further qgrams. avoids pathological byte classes.
                }
            }
            qGramStartPos++; // qGram start is now one less than the last successful loop - add one.

            // Determine final size of hash table:
            final int HASH_SIZE;
            if (POWER_TWO_SIZE > 0) {       // specified by user - must use this size exactly.
                HASH_SIZE = POWER_TWO_SIZE; // total qgram processing above still useful to avoid pathological byte classes (qGramStartPos).
            } else {
                //TODO: or should it be the power of two *one higher* than ceilLogBase2 of total qgrams? What effective margin do we want?
                final int qGramPowerTwoSize = ByteUtils.ceilLogBaseTwo(totalQgrams); // the power of two size bigger or equal to total qgrams.
                HASH_SIZE = MAX_HASH_POWER_TWO_SIZE < qGramPowerTwoSize?
                            MAX_HASH_POWER_TWO_SIZE : qGramPowerTwoSize > MIN_POWER_TWO_SIZE? // but not bigger than the maximum allowed,
                                                      qGramPowerTwoSize : MIN_POWER_TWO_SIZE; // and not smaller than the minimum allowed.
            }

            // Determine bit shift for multiply-shift hash algorithm:
            final int HASH_SHIFT = 64 - HASH_SIZE;

            // Determine max search shift allowed by the qGramStartPos.
            // If we bailed out early due to to many qgrams, then this will be further along than the start of the pattern,
            // and consequently the maximum shift we can support is lower than the full pattern would allow.
            final int MAX_SEARCH_SHIFT = PATTERN_LENGTH - QLEN - qGramStartPos + 1;
            if (MAX_SEARCH_SHIFT < 2) { //TODO: determine optimum cutoff : what shift gives better performance than the fallback?
                return NULL_SEARCH_INFO; // if max shift is not even one, no point in using this searcher, use fallback searcher.
            }

            // Set up the hash table and initialize to the maximum shift allowed given qGramStartPos.
            final int[] SHIFTS = new int[1 << HASH_SIZE];
            Arrays.fill(SHIFTS, MAX_SEARCH_SHIFT);

            // Set up the key values for hashing as we go along the pattern:
            byte[] bytes0; // first step of processing shifts all the key values along one, so bytes0 = bytes1, ...
            byte[] bytes1 = sequence.getMatcherForPosition(qGramStartPos).getMatchingBytes();
            byte[] bytes2 = sequence.getMatcherForPosition(qGramStartPos + 1).getMatchingBytes();
            byte[] bytes3 = sequence.getMatcherForPosition(qGramStartPos + 2).getMatchingBytes();
            int keyValue = 0;
            boolean haveLastKeyValue = false;

            // Process all the qgrams in the pattern from the qGram start pos to one before the end of the pattern.
            final int LAST_PATTERN_POS = PATTERN_LENGTH - 1;
            for (int qGramEnd = qGramStartPos + QLEN - 1; qGramEnd < LAST_PATTERN_POS; qGramEnd++) {

                // Calcluate shift for qgrams at this position:
                final int CURRENT_SHIFT = LAST_PATTERN_POS - qGramEnd;

                // Get the byte arrays for the qGram at the current qGramStart:
                bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;                    // shift byte arrays along one.
                bytes3 = sequence.getMatcherForPosition(qGramEnd).getMatchingBytes(); // get next byte array.

                // Process the qgram permutations as efficiently as possible:
                final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
                if (numberOfPermutations == 1L) { // no permutations to worry about:
                    if (!haveLastKeyValue) { // if we don't have a good last key value, calculate the first 3 elements of it:
                        keyValue = (((bytes0[0] & 0xFF) << 8) |
                                     (bytes1[0] & 0xFF) << 8) |
                                     (bytes2[0] & 0xFF);
                        haveLastKeyValue = true;
                    }
                    keyValue = (keyValue << 8) | (bytes3[0] & 0xFF); // calculate the new qgram.

                    // Calculate the hash from the key and put the shift value in the hash table.
                    final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                    SHIFTS[hash] = CURRENT_SHIFT;

                } else { // more than one permutation to work through.
                    if (haveLastKeyValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                        for (final byte permutationValue : bytes3) {
                            final int permutationKey = ((keyValue << 8) | (permutationValue & 0xFF));
                            final int hash = (int) ((permutationKey * HASH_MULTIPLY) >>> HASH_SHIFT);
                            SHIFTS[hash] = CURRENT_SHIFT;
                        }
                        haveLastKeyValue = false; // after processing the permutations, we don't have a single last key value.

                    } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                        final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                        while (qGramPermutations.hasNext()) {
                            // Calculate the key value:
                            final byte[] permutationValue = qGramPermutations.next();
                            keyValue = ((((permutationValue[0] & 0xFF) << 8) |
                                          (permutationValue[1] & 0xFF) << 8) |
                                          (permutationValue[2] & 0xFF) << 8) |
                                          (permutationValue[3] & 0xFF);
                            // Calculate the hash from the key and put the shift value in the hash table.
                            final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                            SHIFTS[hash] = CURRENT_SHIFT;
                        }
                    }
                }
            }

            // Make shifts for the last qgrams in the pattern negative:

            // Get byte arrays for last q-gram:
            bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;                            // shift byte arrays along one.
            bytes3 = sequence.getMatcherForPosition(LAST_PATTERN_POS).getMatchingBytes(); // get last byte array.

            // Process the last qgram permutations as efficiently as possible:
            final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
            if (numberOfPermutations == 1L) { // no permutations to worry about:
                if (!haveLastKeyValue) { // if we don't have a good last key value, calculate the first 3 elements of it:
                    keyValue = (((bytes0[0] & 0xFF) << 8) |
                                 (bytes1[0] & 0xFF) << 8) |
                                 (bytes2[0] & 0xFF);
                }
                keyValue = (keyValue << 8) | (bytes3[0] & 0xFF); // calculate the new qgram.

                // Calculate the hash from the key and make the shift value negative.
                final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                SHIFTS[hash] = -SHIFTS[hash];

            } else { // more than one permutation to work through.
                if (haveLastKeyValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                    for (final byte permutationValue : bytes3) {
                        final int permutationKey = ((keyValue << 8) | (permutationValue & 0xFF));
                        final int hash = (int) ((permutationKey * HASH_MULTIPLY) >>> HASH_SHIFT);

                        // If the current shift is positive make it negative.
                        final int CURRENT_SHIFT = SHIFTS[hash];
                        if (CURRENT_SHIFT > 0) {
                            SHIFTS[hash] = -CURRENT_SHIFT;
                        }
                    }

                } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                    final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                    while (qGramPermutations.hasNext()) {
                        // Calculate the key value:
                        final byte[] permutationValue = qGramPermutations.next();
                        keyValue = ((((permutationValue[0] & 0xFF) << 8) |
                                      (permutationValue[1] & 0xFF) << 8) |
                                      (permutationValue[2] & 0xFF) << 8) |
                                      (permutationValue[3] & 0xFF);
                        // Calculate the hash from the key and put the shift value in the hash table.
                        final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);

                        // If the current shift is positive make it negative.
                        final int CURRENT_SHIFT = SHIFTS[hash];
                        if (CURRENT_SHIFT > 0) {
                            SHIFTS[hash] = -CURRENT_SHIFT;
                        }
                    }
                }
            }

            return new SearchInfo(SHIFTS, HASH_SHIFT);
        }
    }

    /**
     * A factory for the shift table needed to search backwards.
     */
    private final class BackwardSearchInfoFactory implements ObjectFactory<SearchInfo> {


        //TODO: finish - just a copy of the forward search info right now.
        /**
         * Calculates the shift table for backwards searching.
         */
        @Override
        public SearchInfo create() {
            // Get local copies of fields:
            final SequenceMatcher localSequence = sequence;

            // If the pattern is shorter than one qgram, the fallback searcher will be used instead.
            final int PATTERN_LENGTH = localSequence.length();
            if (PATTERN_LENGTH < QLEN) {
                return NULL_SEARCH_INFO; // no shifts to calculate.
            }

            // Determine the maximum size of the hash table:
            final int MAX_HASH_POWER_TWO_SIZE = (POWER_TWO_SIZE > 0)? POWER_TWO_SIZE
                                                                    : POWER_TWO_SIZE == 0? MAX_POWER_TWO_SIZE
                                                                                         : -POWER_TWO_SIZE;

            // Calculate how many qgrams we have, but stop if we get to more than we can handle with good performance.
            // This will give us the size of the hash table (if automatically selected) and the starting position of
            // the qgram to calculate shifts for, which gives us the maximum distance we can shift.
            // Normally this position is the start of the pattern, but if we discover pathologically large adjacent byte
            // classes, the permutations will swamp the hash table.  In that case, the first qgram we process shifts for
            // isn't the start of the pattern, it's the one at which almost no better values would remain in the hash table.
            final int MAX_TABLE_SIZE = 1 << MAX_HASH_POWER_TWO_SIZE;
            int num0;
            int num1 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 1);
            int num2 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 2);
            int num3 = localSequence.getNumBytesAtPosition(PATTERN_LENGTH - 3);
            int totalQgrams = 0;
            int qGramStartPos;
            //TODO: adapt for backwards searching - we want the opposite position for backwards searching...
            for (qGramStartPos = PATTERN_LENGTH - QLEN; qGramStartPos >= 0; qGramStartPos--) {
                num0 = num1; num1 = num2; num2 = num3; // shift byte counts along.
                num3 = localSequence.getNumBytesAtPosition(qGramStartPos); // get next count.
                totalQgrams += (num0 * num1 * num2 * num3);
                // If we go beyond the max table load, stop further processing.
                if ((totalQgrams >> 2) >= MAX_TABLE_SIZE) { // if there's four times as many qgrams as max table size (quarter bigger than max):
                    qGramStartPos--; // make the sums add up, will be re-added when loop ends.
                    break; // no further value, halt processing of further qgrams. avoids pathological byte classes.
                }
            }
            qGramStartPos++; // qGram start is now one less than the last successful loop - add one.

            // Determine final size of hash table:
            final int HASH_SIZE;
            if (POWER_TWO_SIZE > 0) {       // specified by user - must use this size exactly.
                HASH_SIZE = POWER_TWO_SIZE; // total qgram processing above still useful to avoid pathological byte classes (qGramStartPos).
            } else {
                //TODO: or should it be the power of two *one higher* than ceilLogBase2 of total qgrams? What effective margin do we want?
                final int qGramPowerTwoSize = ByteUtils.ceilLogBaseTwo(totalQgrams); // the power of two size bigger or equal to total qgrams.
                HASH_SIZE = MAX_HASH_POWER_TWO_SIZE < qGramPowerTwoSize?
                        MAX_HASH_POWER_TWO_SIZE : qGramPowerTwoSize > MIN_POWER_TWO_SIZE? // but not bigger than the maximum allowed,
                        qGramPowerTwoSize : MIN_POWER_TWO_SIZE; // and not smaller than the minimum allowed.
            }

            // Determine bit shift for multiply-shift hash algorithm:
            final int HASH_SHIFT = 64 - HASH_SIZE;

            // Determine max search shift allowed by the qGramStartPos.
            // If we bailed out early due to to many qgrams, then this will be further along than the start of the pattern,
            // and consequently the maximum shift we can support is lower than the full pattern would allow.
            final int MAX_SEARCH_SHIFT = PATTERN_LENGTH - QLEN - qGramStartPos + 1;
            if (MAX_SEARCH_SHIFT < 2) { //TODO: determine optimum cutoff : what shift gives better performance than the fallback?
                return NULL_SEARCH_INFO; // if max shift is not even one, no point in using this searcher, use fallback searcher.
            }

            // Set up the hash table and initialize to the maximum shift allowed given qGramStartPos.
            final int[] SHIFTS = new int[1 << HASH_SIZE];
            Arrays.fill(SHIFTS, MAX_SEARCH_SHIFT);

            //TODO: adapt for backwards searching - we want the opposite positions for backwards searching...
            // Set up the key values for hashing as we go along the pattern:
            byte[] bytes0; // first step of processing shifts all the key values along one, so bytes0 = bytes1, ...
            byte[] bytes1 = sequence.getMatcherForPosition(qGramStartPos).getMatchingBytes();
            byte[] bytes2 = sequence.getMatcherForPosition(qGramStartPos + 1).getMatchingBytes();
            byte[] bytes3 = sequence.getMatcherForPosition(qGramStartPos + 2).getMatchingBytes();
            int keyValue = 0;
            boolean haveLastKeyValue = false;

            // Process all the qgrams in the pattern from the qGram start pos to one before the end of the pattern.
            final int LAST_PATTERN_POS = PATTERN_LENGTH - 1;
            for (int qGramEnd = qGramStartPos + QLEN - 1; qGramEnd < LAST_PATTERN_POS; qGramEnd++) {

                // Calcluate shift for qgrams at this position:
                final int CURRENT_SHIFT = LAST_PATTERN_POS - qGramEnd;

                // Get the byte arrays for the qGram at the current qGramStart:
                bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;                    // shift byte arrays along one.
                bytes3 = sequence.getMatcherForPosition(qGramEnd).getMatchingBytes(); // get next byte array.

                // Process the qgram permutations as efficiently as possible:
                final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
                if (numberOfPermutations == 1L) { // no permutations to worry about:
                    if (!haveLastKeyValue) { // if we don't have a good last key value, calculate the first 3 elements of it:
                        keyValue = (((bytes0[0] & 0xFF) << 8) |
                                (bytes1[0] & 0xFF) << 8) |
                                (bytes2[0] & 0xFF);
                        haveLastKeyValue = true;
                    }
                    keyValue = (keyValue << 8) | (bytes3[0] & 0xFF); // calculate the new qgram.

                    // Calculate the hash from the key and put the shift value in the hash table.
                    final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                    SHIFTS[hash] = CURRENT_SHIFT;

                } else { // more than one permutation to work through.
                    if (haveLastKeyValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                        for (final byte permutationValue : bytes3) {
                            final int permutationKey = ((keyValue << 8) | (permutationValue & 0xFF));
                            final int hash = (int) ((permutationKey * HASH_MULTIPLY) >>> HASH_SHIFT);
                            SHIFTS[hash] = CURRENT_SHIFT;
                        }
                        haveLastKeyValue = false; // after processing the permutations, we don't have a single last key value.

                    } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                        final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                        while (qGramPermutations.hasNext()) {
                            // Calculate the key value:
                            final byte[] permutationValue = qGramPermutations.next();
                            keyValue = ((((permutationValue[0] & 0xFF) << 8) |
                                    (permutationValue[1] & 0xFF) << 8) |
                                    (permutationValue[2] & 0xFF) << 8) |
                                    (permutationValue[3] & 0xFF);
                            // Calculate the hash from the key and put the shift value in the hash table.
                            final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                            SHIFTS[hash] = CURRENT_SHIFT;
                        }
                    }
                }
            }

            //TODO: adapt for backwards searching - we want the opposite position for backwards searching...
            // Make shifts for the last qgrams in the pattern negative:

            // Get byte arrays for last q-gram:
            bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;                            // shift byte arrays along one.
            bytes3 = sequence.getMatcherForPosition(LAST_PATTERN_POS).getMatchingBytes(); // get last byte array.

            // Process the last qgram permutations as efficiently as possible:
            final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
            if (numberOfPermutations == 1L) { // no permutations to worry about:
                if (!haveLastKeyValue) { // if we don't have a good last key value, calculate the first 3 elements of it:
                    keyValue = (((bytes0[0] & 0xFF) << 8) |
                            (bytes1[0] & 0xFF) << 8) |
                            (bytes2[0] & 0xFF);
                }
                keyValue = (keyValue << 8) | (bytes3[0] & 0xFF); // calculate the new qgram.

                // Calculate the hash from the key and make the shift value negative.
                final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);
                SHIFTS[hash] = -SHIFTS[hash];

            } else { // more than one permutation to work through.
                if (haveLastKeyValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                    for (final byte permutationValue : bytes3) {
                        final int permutationKey = ((keyValue << 8) | (permutationValue & 0xFF));
                        final int hash = (int) ((permutationKey * HASH_MULTIPLY) >>> HASH_SHIFT);

                        // If the current shift is positive make it negative.
                        final int CURRENT_SHIFT = SHIFTS[hash];
                        if (CURRENT_SHIFT > 0) {
                            SHIFTS[hash] = -CURRENT_SHIFT;
                        }
                    }

                } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                    final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                    while (qGramPermutations.hasNext()) {
                        // Calculate the key value:
                        final byte[] permutationValue = qGramPermutations.next();
                        keyValue = ((((permutationValue[0] & 0xFF) << 8) |
                                (permutationValue[1] & 0xFF) << 8) |
                                (permutationValue[2] & 0xFF) << 8) |
                                (permutationValue[3] & 0xFF);
                        // Calculate the hash from the key and put the shift value in the hash table.
                        final int hash = (int) ((keyValue * HASH_MULTIPLY) >>> HASH_SHIFT);

                        // If the current shift is positive make it negative.
                        final int CURRENT_SHIFT = SHIFTS[hash];
                        if (CURRENT_SHIFT > 0) {
                            SHIFTS[hash] = -CURRENT_SHIFT;
                        }
                    }
                }
            }

            return new SearchInfo(SHIFTS, HASH_SHIFT);
        }

        // Old version of backwards search create:
        /**
         * Calculates the shift table for backwards searching.
         */
        /*
        @Override
        public SearchInfo create() {


            // Initialise constants
            final int   PATTERN_LENGTH   = sequence.length();
            final int   MASK             = TABLE_SIZE - 1;
            final int[] SHIFTS           = new int[TABLE_SIZE];
            final int   MAX_PERMUTATIONS = TABLE_SIZE; // TODO: validate.

            // Initialise shift table to the max possible shift
            // The maximum shift isn't the pattern length, as in Horspool, because we are reading qgrams.
            // The max shift will align the start of the pattern to the position one past the start of the last qgram read.
            // TODO: check for pathological cases where there's no point in processing bits of the pattern
            //       by scanning back from the end to find high permutation values.
            final int LAST_PATTERN_POS = PATTERN_LENGTH - 1;
            final int MAX_SHIFT        = PATTERN_LENGTH - QLEN + 1;
            Arrays.fill(SHIFTS, MAX_SHIFT);

            // Set initial processing states
            int lastHash = 0;
            boolean haveLastHashValue = false;
            byte[] bytes0;
            byte[] bytes1 = sequence.getMatcherForPosition(LAST_PATTERN_POS    ).getMatchingBytes();
            byte[] bytes2 = sequence.getMatcherForPosition(LAST_PATTERN_POS - 1).getMatchingBytes();
            byte[] bytes3 = sequence.getMatcherForPosition(LAST_PATTERN_POS - 2).getMatchingBytes();

            // Process all the qgrams in the pattern from the end to one after the start of the pattern.
            for (int qGramEnd = PATTERN_LENGTH - QLEN; qGramEnd > 0; qGramEnd--) {

                // Calcluate shift for qgrams at this position:
                final int CURRENT_SHIFT = qGramEnd; // TODO: check calculation for backwards match.

                // Get the byte arrays for the qGram at the current qGramStart:
                bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;                    // shift byte arrays along one.
                bytes3 = sequence.getMatcherForPosition(qGramEnd).getMatchingBytes(); // get next byte array.

                // Ensure we don't process too many permutations in pathological cases where multiple byte classes
                // are adjacent to each other.  If there are too many, just set all shifts to the current value.
                final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
                if (numberOfPermutations > MAX_PERMUTATIONS) {  // too many permutations to bother processing.
                    Arrays.fill(SHIFTS, CURRENT_SHIFT);         // just set the entire table to the current shift.
                    haveLastHashValue = false;
                } else {
                    // Process the qgram permutations as efficiently as possible:
                    if (numberOfPermutations == 1L) { // no permutations to worry about.
                        if (!haveLastHashValue) { // if we don't have a good last hash value, calculate the first 3 elements of it:
                            lastHash =                        (bytes0[0] & 0xFF);
                            lastHash = ((lastHash << SHIFT) + (bytes1[0] & 0xFF));
                            lastHash = ((lastHash << SHIFT) + (bytes2[0] & 0xFF));
                            haveLastHashValue = true;
                        }
                        lastHash = ((lastHash << SHIFT) + (bytes3[0] & 0xFF)); // calculate the new element of the qgram.
                        SHIFTS[lastHash & MASK] = CURRENT_SHIFT;
                    } else { // more than one permutation to work through.
                        if (haveLastHashValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                            for (final byte permutationValue : bytes3) {
                                final int permutationHash = ((lastHash << SHIFT) + (permutationValue & 0xFF));
                                SHIFTS[permutationHash & MASK] = CURRENT_SHIFT;
                            }
                            haveLastHashValue = false; // after processing the permutations, we don't have a single last hash value.
                        } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                            final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                            while (qGramPermutations.hasNext()) {
                                final byte[] permutationValue = qGramPermutations.next();
                                lastHash =                        (permutationValue[0] & 0xFF);
                                lastHash = ((lastHash << SHIFT) + (permutationValue[1] & 0xFF));
                                lastHash = ((lastHash << SHIFT) + (permutationValue[2] & 0xFF));
                                lastHash = ((lastHash << SHIFT) + (permutationValue[3] & 0xFF));
                                SHIFTS[lastHash & MASK] = CURRENT_SHIFT;
                            }
                        }
                    }
                }
            }

            // Make shifts for the last qgrams in the pattern negative:

            // Get byte arrays for last q-gram:
            bytes0 = bytes1; bytes1 = bytes2; bytes2 = bytes3;             // shift byte arrays along one.
            bytes3 = sequence.getMatcherForPosition(0).getMatchingBytes(); // get first byte array.

            // Ensure number of permutations aren't excessive:
            final long numberOfPermutations = getNumPermutations(bytes0, bytes1, bytes2, bytes3);
            if (numberOfPermutations > MAX_PERMUTATIONS) {
                // too many permutations to bother processing, all entries become negative:
                for (int tableIndex = 0; tableIndex < SHIFTS.length; tableIndex++) {
                    SHIFTS[tableIndex] = -SHIFTS[tableIndex];
                }
            } else {
                // Process the qgram permutations as efficiently as possible:
                if (numberOfPermutations == 1L) { // no permutations to worry about.
                    if (!haveLastHashValue) {     // if we don't have a good last hash value, calculate the first 3 elements of it:
                        lastHash =                        (bytes0[0] & 0xFF);
                        lastHash = ((lastHash << SHIFT) + (bytes1[0] & 0xFF));
                        lastHash = ((lastHash << SHIFT) + (bytes2[0] & 0xFF));
                    }
                    lastHash = ((lastHash << SHIFT) + (bytes3[0] & 0xFF)); // calculate the new element of the qgram.
                    SHIFTS[lastHash & MASK] = -SHIFTS[lastHash & MASK];
                } else { // more than one permutation to work through.
                    if (haveLastHashValue) { // Then bytes3 must contain all the additional permutations - just go through them.
                        for (final byte permutationValue : bytes3) {
                            final int permutationHash = ((lastHash << SHIFT) + (permutationValue & 0xFF));
                            final int currentShift = SHIFTS[permutationHash & MASK];
                            if (currentShift > 0) {
                                SHIFTS[permutationHash & MASK] = -currentShift;
                            }
                        }
                    } else { // permutations may exist anywhere and in more than one place, use a BytePermutationIterator:
                        final BytePermutationIterator qGramPermutations = new BytePermutationIterator(bytes0, bytes1, bytes2, bytes3);
                        while (qGramPermutations.hasNext()) {
                            final byte[] permutationValue = qGramPermutations.next();
                            lastHash =                        (permutationValue[0] & 0xFF);
                            lastHash = ((lastHash << SHIFT) + (permutationValue[1] & 0xFF));
                            lastHash = ((lastHash << SHIFT) + (permutationValue[2] & 0xFF));
                            lastHash = ((lastHash << SHIFT) + (permutationValue[3] & 0xFF));
                            final int currentShift = SHIFTS[lastHash & MASK];
                            if (currentShift > 0) {
                                SHIFTS[lastHash & MASK] = -currentShift;
                            }
                        }
                    }
                }
            }
            return SHIFTS;

            return null;
        } */
    }

    /*******************
     * Utility methods *
     *******************/

    private long getNumPermutations(final byte[] values1, final byte[] values2, final byte[] values3, final byte[] values4) {
        return values1.length * values2.length * values3.length * values4.length;
    }

}
