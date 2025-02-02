/*
 * Copyright Matt Palmer 2012, All rights reserved.
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

package net.byteseek.searcher.multisequence;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import net.byteseek.io.reader.windows.Window;
import net.byteseek.io.reader.WindowReader;
import net.byteseek.matcher.multisequence.MultiSequenceMatcher;
import net.byteseek.matcher.multisequence.TrieMultiSequenceMatcher;
import net.byteseek.matcher.sequence.SequenceMatcher;
import net.byteseek.searcher.SearchResult;
import net.byteseek.searcher.SearchUtils;

/**
 * This class implements the {@link net.byteseek.searcher.Searcher} interface,
 * extending {@link AbstractMultiSequenceSearcher}.
 * <p>
 * It searches across a byte array or a {@link WindowReader} for a {@link MultiSequenceMatcher},
 * using the naive technique of searching for the MultiSequenceMatcher at each position,
 * until it either finds a match or runs out of search space.
 * <p>
 * Other MultiSequenceSearchers usually offer better performance (albeit with more memory
 * usage) for most cases, although when a large number of sequences are being searched
 * for using an efficient MultiSequenceMatcher such as a {@link TrieMultiSequenceMatcher}
 * then this may be efficient for short searches.
 * <p>
 * No preparation needs to be done in order to search, so for one-off short searches
 * this may also be faster than other methods.
 * 
 * @author Matt Palmer
 */
public class MultiSequenceMatcherSearcher extends AbstractMultiSequenceSearcher {

    /**
     * Constructs a MultiSequenceMatcherSearcher.
     * 
     * @param matcher The MultiSequenceMatcher to search for.
     */
    public MultiSequenceMatcherSearcher(final MultiSequenceMatcher matcher) {
        super(matcher);
    }
    
    
    /**
     * {@inheritDoc}
     */
    @Override
    protected List<SearchResult<SequenceMatcher>> doSearchForwards(final WindowReader reader,
        final long fromPosition, final long toPosition) throws IOException {
        // Initialise:
        final MultiSequenceMatcher matcher = sequences;  
        long searchPosition = fromPosition > 0? 
                              fromPosition : 0;
        
        // While there is data still to search in:
        Window window;
        while (searchPosition <= toPosition &&
               (window = reader.getWindow(searchPosition)) != null) {

            // Calculate bounds for searching over this window:
            final int availableSpace = window.length() - reader.getWindowOffset(searchPosition);
            final long endWindowPosition = searchPosition + availableSpace;
            final long lastPosition = endWindowPosition < toPosition?
                                      endWindowPosition : toPosition;
            
            // Search forwards up to the end of this window:
            while (searchPosition <= lastPosition) {
                final Collection<SequenceMatcher> matches = matcher.allMatches(reader, searchPosition);
                if (!matches.isEmpty()) {
                    return SearchUtils.resultsAtPosition(searchPosition, matches);
                }
                searchPosition++;
            }
            
        }
        return SearchUtils.noResults();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<SearchResult<SequenceMatcher>> searchForwards(final byte[] bytes, 
        final int fromPosition, final int toPosition) {
        
        // Initialise:
        final MultiSequenceMatcher matcher = sequences;
        
        // Calculate bounds for the search:
        final int lastPossiblePosition = bytes.length - sequences.getMinimumLength();
        final int lastPosition = toPosition < lastPossiblePosition?
                                 toPosition : lastPossiblePosition;
        int searchPosition = fromPosition > 0?
                             fromPosition : 0;
        
        // Search forwards up to the last possible position:
        while (searchPosition <= lastPosition) {
            final Collection<SequenceMatcher> matches = matcher.allMatches(bytes, searchPosition);
            if (!matches.isEmpty()) {
                return SearchUtils.resultsAtPosition(searchPosition, matches);
            }
            searchPosition++;
        }
        return SearchUtils.noResults();           
    }


    /**
     * {@inheritDoc}
     */    
    @Override
    protected List<SearchResult<SequenceMatcher>> doSearchBackwards(final WindowReader reader, 
        final long fromPosition, final long toPosition) throws IOException {
        // Initialise:
        final MultiSequenceMatcher matcher = sequences;
        long searchPosition = withinLength(reader, fromPosition);
        
        // While there is data to search in:
        Window window;
        while (searchPosition >= toPosition &&
               (window = reader.getWindow(searchPosition)) != null) {
            
            // Calculate bounds for searching back across this window:
            final long windowStartPosition = window.getWindowPosition();
            final long lastSearchPosition = toPosition > windowStartPosition?
                                            toPosition : windowStartPosition;
            
            // Search backwards:
            while (searchPosition >= lastSearchPosition) {
                final Collection<SequenceMatcher> matches = matcher.allMatches(reader, searchPosition);
                if (!matches.isEmpty()) {
                    return SearchUtils.resultsAtPosition(searchPosition, matches);
                }
                searchPosition--;
            }
        }
        
        return SearchUtils.noResults();
    }


    /**
     * {@inheritDoc}
     */    
    @Override
    public List<SearchResult<SequenceMatcher>> searchBackwards(final byte[] bytes, 
        final int fromPosition, final int toPosition) {
        
        // Initialise:
        final MultiSequenceMatcher matcher = sequences;
        
        // Calculate safe bounds for the search:
        final int lastPosition = toPosition > 0?
                                 toPosition : 0;
        final int firstPossiblePosition = bytes.length - sequences.getMinimumLength();
        int searchPosition = fromPosition < firstPossiblePosition?
                             fromPosition : firstPossiblePosition;
        
        // Search backwards:
        while (searchPosition >= lastPosition) {
            final Collection<SequenceMatcher> matches = matcher.allMatches(bytes, searchPosition);            
            if (!matches.isEmpty()) {
                return SearchUtils.resultsAtPosition(searchPosition, matches);
            }
            searchPosition--;
        }
        return SearchUtils.noResults();
    }

    
    /**
     * No preparation is necessary for this searcher.
     */    
    @Override
    public void prepareForwards() {
        //  nothing to prepare.
    }

    
    /**
     * No preparation is necessary for this searcher.
     */    
    @Override
    public void prepareBackwards() {
        // nothing to prepare.
    }
    
}
