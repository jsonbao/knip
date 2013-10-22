/*
 * ------------------------------------------------------------------------
 *
 *  Copyright (C) 2003 - 2013
 *  University of Konstanz, Germany and
 *  KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * --------------------------------------------------------------------- *
 *
 */
package org.knime.knip.core.ops.interval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.ops.operation.UnaryOperation;
import net.imglib2.ops.operation.randomaccessibleinterval.unary.morph.StructuringElementCursor;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import org.knime.knip.core.util.NeighborhoodUtils;

/**
 * Operation to Compute local maxima within a given image. Maxima computation can be done in any dimensionality desired.
 *
 * @author Tino Klingebiel, University of Konstanz
 * @author Jonathan Hale, University of Konstanz
 * @param <T>
 */

public class MaximumFinder<T extends RealType<T>> implements
        UnaryOperation<RandomAccessibleInterval<T>, RandomAccessibleInterval<BitType>> {

    private final double m_noise;

    private final double m_suppression;

    private long[][] m_structEl;

    private ArrayList<AnalyticPoint> pList;

    /**
     * @param noise
     * @param suppression
     */
    public MaximumFinder(final double noise, final double suppression) {
        m_noise = noise;
        m_suppression = suppression;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RandomAccessibleInterval<BitType> compute(final RandomAccessibleInterval<T> input,
                                                     final RandomAccessibleInterval<BitType> output) {

        pList = new ArrayList<AnalyticPoint>();

        Cursor<T> cur = Views.iterable(input).localizingCursor();
        RandomAccess<T> ra = input.randomAccess(input);

        m_structEl = NeighborhoodUtils.reworkStructuringElement(NeighborhoodUtils.get8ConStructuringElement(input.numDimensions()));

        boolean candidate;
        double act;

        final T v = Views.iterable(input).firstElement().createVariable();
        final StructuringElementCursor<T> strelCursor = new StructuringElementCursor<T>(Views.extendValue( input, v ).randomAccess(), m_structEl);

        //iterate through the image
        while (cur.hasNext()) {
            cur.fwd();

            candidate = true;
            act = cur.get().getRealDouble();

            strelCursor.relocate( cur );

            while (strelCursor.hasNext()) {
                strelCursor.next();

                if (strelCursor.get().getRealDouble() > act) {
                    /*
                     * A candidate has no higher intensity pixels in his
                     * neighborhood. When reaching this point, this
                     * condition does not hold and this is no candidate.
                     */
                    candidate = false;
                    break;
                }
            }

            if (candidate) {
                int[] coordinates = new int[input.numDimensions()];
                cur.localize(coordinates);

                AnalyticPoint p = new AnalyticPoint(coordinates, act);
                pList.add(p);
            }
        }
        /*
         * Analyze the maxima candidates. Find out wich ones are real local maxima.
         */
        analyzeCandidates(input, input /* dimensions */, output);
        return output;
    }

    /*
     * Status Image Type should not be
     * anything higher than ByteType
     */
    /**
     * @param input
     * @param dimensions
     * @param output
     */

    protected void analyzeCandidates(final RandomAccessibleInterval<T> input, final Dimensions dimensions,
                                     final RandomAccessibleInterval<BitType> output) {

        /*
         * Status structure to avoid running over pixels more than once when
         * computing plateau centers.
         */
        Img<BitType> status = new ArrayImgFactory<BitType>().create(output, new BitType());
        /*
         * Status structure to mark all previous reached pixels. SInce we stark with the
         * highest intensity pixels we know, when reaching a pixel marked as processed,
         * that we can sip the current operation, because the initial candidate can
         * not be a local max, because it is connected to a previous computed local max
         * or a region connected to one. Since we can only reach point in noise tolerance
         * our candidate is now real max.
         */
        Img<BitType> processed = new ArrayImgFactory<BitType>().create(output, new BitType());

        RandomAccess<T> raInput = input.randomAccess();
        RandomAccess<T> act = input.randomAccess();
        RandomAccess<BitType> raOutput = output.randomAccess();
        RandomAccess<BitType> raStat = status.randomAccess();
        RandomAccess<BitType> raProc = processed.randomAccess();


        int numDimensions = dimensions.numDimensions(); //frequently used shortcut

        for (AnalyticPoint p : pList) {
            /*
             * check if the actual candidate was reached by previous steps.
             * If it was, it is either a member of a plateau or connected
             * to a real max and thus no real local max. Ignore it.
             */

            raStat.setPosition(p.getPosition());

            if (raStat.get().get()) {
                continue;
            }

            raProc.setPosition(p.getPosition());

            if(raProc.get().get()) {
                continue;
            }

            act.setPosition(p.getPosition());
            raInput.setPosition(p.getPosition());

            for (long[] offset : m_structEl) {
                raInput.move(offset);

                if (isWithin(raInput, dimensions)) {
                    if (Math.abs(act.get().getRealDouble() - raInput.get().getRealDouble()) <= m_noise) {

                        long[] myPosi =
                                analyzeneighborhood(input, status, processed, act, dimensions);

                        if (myPosi != null) {
                            long[] newPos = new long[numDimensions];

                            long lastDimVal = myPosi[numDimensions];
                            for (int i = 0; i < numDimensions; ++i) {
                                newPos[i] = myPosi[i] / lastDimVal;
                            }

                            long mindist = 10000;

                            long d, v;

                            AnalyticPoint minPoint = pList.get(0);
                            for (AnalyticPoint k : pList) {
                                d = 0;
                                for (int i = 0; i < newPos.length; ++i) {
                                    v = newPos[i] - k.getPosition()[i];
                                    d += v * v;
                                }
                                if (d <= mindist) {
                                    minPoint = k;
                                    mindist = d;
                                }
                            }

                            minPoint.setMax(true);
                        } else {
                            /*
                             * When we reach this point the initial candidate is
                             * most likely not the real local max within the region.
                             * Mark it, so that it will not be set as a local max.
                             * The real max is set 3 lines above and could still
                             * be the candidate.
                             */
                            p.setMax(false);
                            break;
                        }
                    }
                }

            }
        }
        for (AnalyticPoint p : pList) {
            raOutput.setPosition(p.getPosition());
            raOutput.get().setReal(255);
        }

    }

    /**
     * Suppression of Max Points within a given radius.
     */
    protected void doSuppression() {

        Collections.sort(pList);

        /*Build a distance Matrix (To avoid running
         * over everything more often than we need to
         */

        //TODO: This code removes all maxima which have a bigger maxima
        // in suppression range. Use iterators please.
        int size = pList.size();
        for (int i = 0; i < size; ++i) {
            for (int j = i + 1; j < size; ++j) {
                if (pList.get(i).distanceTo(pList.get(j)) < m_suppression) {
                    pList.remove(j);
                    size--;
                    j--;
                }
            }
        }
    }

    /**
     * Analyze the Neighbor candidates. Find Plateaus, compute Center.
     * @param rndAccessibleInterval
     *
     * @param status - The 3 dimensional Status Structure.
     * @param processed
     * @param ra - The Random access (Position) within the original image.
     * @param dimensions - Dimensions of the Image.
     * @param initialIntensity
     * @return null when the random access was not on a real maximum or the computed real max.
     */
    protected long[] analyzeneighborhood(final RandomAccessibleInterval<T> rndAccessibleInterval,
                                         final RandomAccessibleInterval<BitType> status,
                                         final RandomAccessibleInterval<BitType> processed,
                                         final RandomAccess<T> ra,
                                         final Dimensions dimensions) {

        int numDimensions = dimensions.numDimensions(); //frequently used shortcut

        double initialIntensity = ra.get().getRealDouble();

        RandomAccess<BitType> raStat = status.randomAccess();
        RandomAccess<BitType> raProc = processed.randomAccess();
        HashSet<int[]> next = new HashSet<int[]>();
        HashSet<int[]> all = new HashSet<int[]>();

        long[] retVal = new long[numDimensions+1]; //TODO why +1?
        int[] mpos = new int[numDimensions];

        ra.localize(retVal);
        ra.localize(mpos);

        retVal[numDimensions] = 1;
        next.add(mpos);
        all.add(mpos);

        while (next.size() > 0) {
            HashSet<int[]> newNext = new HashSet<int[]>();

            for (int[] act : next) {
                ra.setPosition(act);
                raStat.setPosition(act);
                raProc.setPosition(act);

                /*
                 * We reached a previous analyzed region.
                 * So this point belongs either to a local
                 * max, or to a region, that is neighbor
                 * to a local max. Thus this point can not
                 * be a local max.
                 */
                if (raProc.get().get()) {
                    for (int[] ac : all) {
                        raStat.setPosition(ac);
                        raStat.get().set(false);
                        raProc.setPosition(ac);
                        raProc.get().set(true);
                    }
                    return null;
                }

                raStat.get().set(true);

                for (long[] offset : m_structEl) {
                    ra.move(offset);
                    if (isWithin(ra, dimensions)) {

                        raStat.setPosition(ra);
                        raProc.setPosition(ra);

                        if (ra.get().getRealDouble() >= initialIntensity - m_noise) {
                            if (raStat.get().get()) {
                                continue; //"already true"
                            } else {
                                raStat.get().set(true);
                            }

                            if (Math.abs(ra.get().getRealDouble() - initialIntensity) <= m_noise) {
                                mpos = new int[numDimensions];
                                for (int i = 0; i < numDimensions; ++i) {
                                    retVal[i] += ra.getIntPosition(i);
                                    mpos[i]    = ra.getIntPosition(i);
                                }
                                retVal[numDimensions]++;

                                if (!all.contains(mpos)) {
                                    newNext.add(mpos);
                                    all.add(mpos);
                                }
                            } else {
                                /*
                                 * The point is higher that the candidate + noise
                                 * and was reached by traversing all neighbors
                                 * to the candidate that are within noisetolerance
                                 * thus the candidate is no real max.
                                 */
                                for (int[] ac : all) {
                                    raStat.setPosition(ac);
                                    raStat.get().set(false);
                                    raProc.setPosition(ac);
                                    raProc.get().set(true);
                                }
                                return null;
                            }
                        }
                    }
                }
            }
            next = newNext;
        }

        for (int[] act : all) {
            /*
             * Computing done. Mark all pixels traversed (pixels within
             * noisetolerance of the candidate and connected to it) as
             * processed.
             * When reaching a previous processed pixel in future steps
             * we know, that the initial candidate is no local max.
             */
            raStat.setPosition(act);
            raStat.get().set(false);
            raProc.setPosition(act);
            raProc.get().set(true);
        }

        return retVal;
    }

    /**
     * Check if a given Position is within our bounds.
     * Optimized for dimensions.
     *
     * @param ra - Random access on the Position.
     * @param dimensions - The dimensions of our given view as Dimension.
     * @return - true if the Position is within bounds, false otherwise.
     */
    protected boolean isWithin(final RandomAccess<T> ra, final Dimensions dimensions) {
        //Iterates through every dimension and checks if point is within bounds
        int pos;
        for (int i = 0; i < dimensions.numDimensions(); ++i) {
            pos = ra.getIntPosition(i);
            if (pos >= dimensions.dimension(i) || pos < 0) {
                return false; //no need to check other dimensions
            }
        }
        return true;
    }

    /**
     * Check if a given Position is within our bounds.
     * Optimized for long arrays.
     *
     * @param ra - Random access on the Position.
     * @param dimensions - The dimensions of our given view as long[]
     * @return - true if the Position is within bounds, false otherwise.
     */
    protected boolean isWithin(final RandomAccess<T> ra, final long[] dimensions) {
        //Iterates through every dimension and checks if point is within bounds
        int pos;
        int i = 0;
        for (long d : dimensions) {
            pos = ra.getIntPosition(i++);

            if (pos >= d || pos < 0) {
                return false; //no need to check other dimensions
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UnaryOperation<RandomAccessibleInterval<T>, RandomAccessibleInterval<BitType>> copy() {
        return new MaximumFinder<T>(m_noise, m_suppression);
    }

}
