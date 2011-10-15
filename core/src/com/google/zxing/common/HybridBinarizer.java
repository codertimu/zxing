// -*- mode:java; tab-width:2; indent-tabs-mode:nil; c-basic-offset:2 -*-
/*
 * Copyright 2009 ZXing authors
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

package com.google.zxing.common;

import com.google.zxing.Binarizer;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;

/**
 * This class implements a local thresholding algorithm, which while slower than the
 * GlobalHistogramBinarizer, is fairly efficient for what it does. It is designed for
 * high frequency images of barcodes with black data on white backgrounds. For this application,
 * it does a much better job than a global blackpoint with severe shadows and gradients.
 * However it tends to produce artifacts on lower frequency images and is therefore not
 * a good general purpose binarizer for uses outside ZXing.
 *
 * This class extends GlobalHistogramBinarizer, using the older histogram approach for 1D readers,
 * and the newer local approach for 2D readers. 1D decoding using a per-row histogram is already
 * inherently local, and only fails for horizontal gradients. We can revisit that problem later,
 * but for now it was not a win to use local blocks for 1D.
 *
 * This Binarizer is the default for the unit tests and the recommended class for library users.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class HybridBinarizer extends GlobalHistogramBinarizer {

  // This class uses 5x5 blocks to compute local luminance, where each block is 8x8 pixels.
  // So this is the smallest dimension in each axis we can accept.
  private static final int BLOCK_SIZE_POWER = 3;
  private static final int BLOCK_SIZE = 1 << BLOCK_SIZE_POWER;
  private static final int BLOCK_SIZE_MASK = BLOCK_SIZE - 1;
  private static final int MINIMUM_DIMENSION = BLOCK_SIZE * 5;

  private BitMatrix matrix;

  public HybridBinarizer(LuminanceSource source) {
    super(source);
  }

  public BitMatrix getBlackMatrix() throws NotFoundException {
    // Calculates the final BitMatrix once for all requests. This could be called once from the
    // constructor instead, but there are some advantages to doing it lazily, such as making
    // profiling easier, and not doing heavy lifting when callers don't expect it.
    if (matrix != null) {
      return matrix;
    }
    LuminanceSource source = getLuminanceSource();
    if (source.getWidth() >= MINIMUM_DIMENSION && source.getHeight() >= MINIMUM_DIMENSION) {
      byte[] luminances = source.getMatrix();
      int width = source.getWidth();
      int height = source.getHeight();
      int subWidth = width >> BLOCK_SIZE_POWER;
      if ((width & BLOCK_SIZE_MASK) != 0) {
        subWidth++;
      }
      int subHeight = height >> BLOCK_SIZE_POWER;
      if ((height & BLOCK_SIZE_MASK) != 0) {
        subHeight++;
      }
      int[][] blackPoints = calculateBlackPoints(luminances, subWidth, subHeight, width, height);

      BitMatrix newMatrix = new BitMatrix(width, height);
      calculateThresholdForBlock(luminances, subWidth, subHeight, width, height, blackPoints, newMatrix);
      matrix = newMatrix;
    } else {
      // If the image is too small, fall back to the global histogram approach.
      matrix = super.getBlackMatrix();
    }
    return matrix;
  }

  public Binarizer createBinarizer(LuminanceSource source) {
    return new HybridBinarizer(source);
  }

  // For each 8x8 block in the image, calculate the average black point using a 5x5 grid
  // of the blocks around it. Also handles the corner cases (fractional blocks are computed based
  // on the last 8 pixels in the row/column which are also used in the previous block).
  private static void calculateThresholdForBlock(byte[] luminances,
                                                 int subWidth,
                                                 int subHeight,
                                                 int width,
                                                 int height,
                                                 int[][] blackPoints,
                                                 BitMatrix matrix) {
    for (int y = 0; y < subHeight; y++) {
      int yoffset = y << BLOCK_SIZE_POWER;
      if ((yoffset + BLOCK_SIZE) >= height) {
        yoffset = height - BLOCK_SIZE;
      }
      for (int x = 0; x < subWidth; x++) {
        int xoffset = x << BLOCK_SIZE_POWER;
        if ((xoffset + BLOCK_SIZE) >= width) {
            xoffset = width - BLOCK_SIZE;
        }
        int left = x > 1 ? x : 2;
        left = left < subWidth - 2 ? left : subWidth - 3;
        int top = y > 1 ? y : 2;
        top = top < subHeight - 2 ? top : subHeight - 3;
        int sum = 0;
        for (int z = -2; z <= 2; z++) {
          int[] blackRow = blackPoints[top + z];
          sum += blackRow[left - 2] + blackRow[left - 1] + blackRow[left] + blackRow[left + 1] + blackRow[left + 2];
        }
        int average = sum / 25;
        threshold8x8Block(luminances, xoffset, yoffset, average, width, matrix);
      }
    }
  }

  // Applies a single threshold to an 8x8 block of pixels.
  private static void threshold8x8Block(byte[] luminances,
                                        int xoffset,
                                        int yoffset,
                                        int threshold,
                                        int stride,
                                        BitMatrix matrix) {
    for (int y = 0, offset = yoffset * stride + xoffset; y < BLOCK_SIZE; y++, offset += stride) {
      for (int x = 0; x < BLOCK_SIZE; x++) {
        // Comparison needs to be <= so that black == 0 pixels are black even if the threshold is 0
        if ((luminances[offset + x] & 0xFF) <= threshold) {
          matrix.set(xoffset + x, yoffset + y);
        }
      }
    }
  }

  // Esimates blackPoint from previously calculated neighbor esitmates
  private static int getBlackPointFromNeighbors(int[][] blackPoints, int x, int y) {
    return (blackPoints[y-1][x] +
            2*blackPoints[y][x-1] +
            blackPoints[y-1][x-1]) >> 2;
  }

  // Calculates a single black point for each 8x8 block of pixels and saves it away.
  private static int[][] calculateBlackPoints(byte[] luminances,
                                              int subWidth,
                                              int subHeight,
                                              int width,
                                              int height) {
    int[][] blackPoints = new int[subHeight][subWidth];
    for (int y = 0; y < subHeight; y++) {
      int yoffset = y << BLOCK_SIZE_POWER;
      if ((yoffset + BLOCK_SIZE) >= height) {
        yoffset = height - BLOCK_SIZE;
      }
      for (int x = 0; x < subWidth; x++) {
        int xoffset = x << BLOCK_SIZE_POWER;
        if ((xoffset + BLOCK_SIZE) >= width) {
            xoffset = width - BLOCK_SIZE;
        }
        int sum = 0;
        int min = 0xFF;
        int max = 0;
        for (int yy = 0, offset = yoffset * width + xoffset; yy < BLOCK_SIZE; yy++, offset += width) {
          for (int xx = 0; xx < BLOCK_SIZE; xx++) {
            int pixel = luminances[offset + xx] & 0xFF;
            sum += pixel;
            if (pixel < min) {
              min = pixel;
            }
            if (pixel > max) {
              max = pixel;
            }
          }
        }

        // See
        // http://groups.google.com/group/zxing/browse_thread/thread/d06efa2c35a7ddc0

        // The default estimate is the average of the values in the block
        int average = sum >> 6;

        if (max - min <= 24) {
          // If variation wihthin the block is low, assume this is a
          // block with only light or only dark pixels.

          // The default assumption is that the block is light/background.
          // Since no estimate for the level of dark pixels
          // exists locally, use half the min for the block.
          average = min >> 1;
          
          if (y > 0 && x > 0) {
            // Correct the "white/background" assumption for blocks
            // that have neighbors by comparing the pixels in this
            // block to the previously calculated blackpoints. This is
            // based on the fact that dark barcode symbology is always
            // surrounded by some amount of light background for which
            // reasonable blackpoint esimates were made. The bp estimated
            // at the bondaries is used for the interior.
          
            // The (min < bp) seems pretty arbitrary but works better than
            // other heurstics that were tried.

            int bp = getBlackPointFromNeighbors(blackPoints, x, y);
            if (min < bp) {
              average = bp;
            }
          }
        }
        blackPoints[y][x] = average;
      }
    }
    return blackPoints;
  }

}
