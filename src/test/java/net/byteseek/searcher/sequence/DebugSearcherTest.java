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

import net.byteseek.io.reader.FileReader;
import net.byteseek.io.reader.WindowReader;
import net.byteseek.matcher.MatchResult;

import net.byteseek.searcher.SearchIterator;
import org.junit.Before;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A working scratch pad for debugging searchers.   No tests should normally run from here.
 *
 * Created by matt on 11/04/17.
 */
public class DebugSearcherTest {

    private Random random = new Random(0);
    private SequenceSearcher searcher;
    private String resourceName = "/romeoandjuliet.txt"; //"/hsapiensdna.txt";
    private String pattern      = "some noyse Lady, come from that nest\r\nOf death, contagion, and v";
    private byte[] patternbytes;
    private byte[] data;

    @Before
    public void createSearcher() {
        //searcher = new SequenceMatcherSearcher(pattern);

        //data = bytesFrom(resourceName);
        //patternbytes = getRandomPattern(data, 512);
        pattern      = " ";
        data = pattern.getBytes();
        searcher     = new ShiftOrUnrolledSearcher(pattern);
    }


    //@Test
    public void testSearcherBytesForwards() {
        int result = searcher.searchSequenceForwards(data);
    }

    //@Test
    public void testSearcherBytesBackwards() throws IOException {
        SearchIterator iterator = new SearchIterator(searcher, bytesFrom(resourceName));
        long pos = Long.MAX_VALUE;
        while (iterator.hasNext()) {
            List<MatchResult> results = iterator.next();
            MatchResult firstResult = results.get(0);
            pos = firstResult.getMatchPosition();
        }
    }

    //@Test
    public void testSearcherReaderForwards() throws IOException {
        long result = searcher.searchSequenceForwards(readerFrom(resourceName),
                0, 0);
    }

    //@Test
    public void testSearcherReaderBackwards() throws IOException {
        long result = searcher.searchSequenceBackwards(readerFrom(resourceName),
                0 ,0 );
    }


    //---------------------------------------------------------------------------------------------

    private byte[] bytesFrom(String resourceName)  {
        return getBytes(resourceName);
    }

    private WindowReader readerFrom(String resourceName) {
        try {
            return new FileReader(getFile(resourceName));
        } catch (IOException io) {
            throw new RuntimeException("IO Exception occured reading file", io);
        }
    }

    private byte[] getRandomPattern(byte[] dataToSearch, int length) {
        length = length > 0? length : 1; // ensure we have at least a length of one.
        int position = random.nextInt(dataToSearch.length - length - 1);
        byte[] result = Arrays.copyOfRange(dataToSearch, position, position + length);
        return result;
    }

    private byte[] getBytes(final String resourceName) {
        try {
            File file = getFile(resourceName);
            InputStream is = new FileInputStream(file);
            long length = file.length();
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            int numRead = 0;
            while (offset < bytes.length
                    && (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
                offset += numRead;
            }
            is.close();
            return bytes;
        } catch (IOException io) {
            throw new RuntimeException("IO Exception occured reading data", io);
        }
    }

    private File getFile(final String resourceName) {
        URL url = this.getClass().getResource(resourceName);
        return new File(url.getPath());
    }

}
