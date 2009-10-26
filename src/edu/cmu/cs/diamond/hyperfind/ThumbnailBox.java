/*
 *  HyperFind, an search application for the OpenDiamond platform
 *
 *  Copyright (c) 2007-2009 Carnegie Mellon University
 *  All rights reserved.
 *
 *  HyperFind is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, version 2.
 *
 *  HyperFind is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with HyperFind. If not, see <http://www.gnu.org/licenses/>.
 *
 *  Linking HyperFind statically or dynamically with other modules is
 *  making a combined work based on HyperFind. Thus, the terms and
 *  conditions of the GNU General Public License cover the whole
 *  combination.
 * 
 *  In addition, as a special exception, the copyright holders of
 *  HyperFind give you permission to combine HyperFind with free software
 *  programs or libraries that are released under the GNU LGPL or the
 *  Eclipse Public License 1.0. You may copy and distribute such a system
 *  following the terms of the GNU GPL for HyperFind and the licenses of
 *  the other code concerned, provided that you include the source code of
 *  that other code when and as the GNU GPL requires distribution of source
 *  code.
 *
 *  Note that people who make modified versions of HyperFind are not
 *  obligated to grant this special exception for their modified versions;
 *  it is their choice whether to do so. The GNU General Public License
 *  gives permission to release a modified version without this exception;
 *  this exception also makes it possible to release a modified version
 *  which carries forward this exception.
 */

package edu.cmu.cs.diamond.hyperfind;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.*;

import edu.cmu.cs.diamond.opendiamond.*;

public class ThumbnailBox extends JPanel {
    private Search search;

    private SearchFactory factory;

    final private StatisticsBar stats;

    final private ScheduledExecutorService timerExecutor = Executors
            .newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> statsTimerFuture;

    private final JButton stopButton;

    private final JButton startButton;

    private final JList list;

    public ThumbnailBox(JButton stopButton, JButton startButton, JList list,
            StatisticsBar stats) {
        super();

        this.stopButton = stopButton;
        this.startButton = startButton;
        this.stats = stats;
        this.list = list;

        setLayout(new BorderLayout());

        JScrollPane jsp = new JScrollPane(list);
        jsp
                .setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        jsp
                .setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        add(jsp);

        add(stats, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(600, 600));
    }

    private void startStatsTimer() {
        statsTimerFuture = timerExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    final Map<String, ServerStatistics> serverStats = search
                            .getStatistics();

                    boolean hasStats = false;
                    for (ServerStatistics s : serverStats.values()) {
                        if (s.getTotalObjects() != 0) {
                            hasStats = true;
                            break;
                        }
                    }
                    if (hasStats) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                stats.update(serverStats);
                            }
                        });
                    } else {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                stats
                                        .setIndeterminateMessage("Waiting for First Results");
                            }
                        });
                    }
                } catch (SearchClosedException e1) {
                    return;
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void stop() throws InterruptedException {
        search.close();
        if (statsTimerFuture != null) {
            statsTimerFuture.cancel(true);
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
            }
        });
    }

    public void start(Search s, SearchFactory f,
            final Collection<String> patchAttributes) {
        search = s;
        factory = f;

        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        stats.setIndeterminateMessage("Initializing Search");
        startStatsTimer();

        final DefaultListModel model = new DefaultListModel();

        list.setModel(model);

        new SwingWorker<Object, ResultIcon>() {
            @Override
            protected Object doInBackground() throws Exception {
                try {
                    while (true) {
                        Result r = search.getNextResult();
                        if (r == null) {
                            break;
                        }
                        System.out.println(r);

                        byte[] thumbData = r.getValue("thumbnail.jpeg");
                        BufferedImage thumb = null;
                        if (thumbData != null) {
                            ByteArrayInputStream in = new ByteArrayInputStream(
                                    thumbData);
                            try {
                                thumb = ImageIO.read(in);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if (thumb == null) {
                            thumb = new BufferedImage(200, 150,
                                    BufferedImage.TYPE_INT_RGB);
                        }

                        // draw patches
                        Graphics2D g = thumb.createGraphics();
                        g.setColor(Color.GREEN);
                        int origW = Util.extractInt(r.getValue("_cols.int"));
                        int origH = Util.extractInt(r.getValue("_rows.int"));
                        g.scale((double) thumb.getWidth() / (double) origW,
                                (double) thumb.getHeight() / (double) origH);

                        try {
                            for (String p : patchAttributes) {
                                System.out.println(p);
                                byte[] patch = r.getValue(p);
                                if (patch != null) {
                                    drawPatches(g, patch);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            throw e;
                        }
                        g.dispose();

                        final ResultIcon resultIcon = new ResultIcon(
                                new ImageIcon(thumb), r.getObjectIdentifier());

                        publish(resultIcon);
                    }
                } finally {
                    stop();
                }
                return null;
            }

            @Override
            protected void process(List<ResultIcon> chunks) {
                for (ResultIcon resultIcon : chunks) {
                    model.addElement(resultIcon);
                }
            }
        }.execute();
    }

    private void drawPatches(Graphics2D g, byte[] patches) {
        ByteBuffer bb = ByteBuffer.wrap(patches);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        int count = bb.getInt();
        double distance = bb.getDouble();

        for (int i = 0; i < count; i++) {
            int x0 = bb.getInt();
            int y0 = bb.getInt();
            int x1 = bb.getInt();
            int y1 = bb.getInt();

            Rectangle r = new Rectangle(x0, y0, x1 - x0, y1 - y0);
            System.out.println(r);
            g.draw(r);
        }
    }
}
