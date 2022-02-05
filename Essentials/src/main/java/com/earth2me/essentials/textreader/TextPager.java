package com.earth2me.essentials.textreader;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.I18n;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.earth2me.essentials.I18n.tl;

public class TextPager {
    private final transient IText text;
    private final transient boolean onePage;

    public TextPager(final IText text) {
        this(text, false);
    }

    public TextPager(final IText text, final boolean onePage) {
        this.text = text;
        this.onePage = onePage;
    }

    public void showPage(final String pageStr, final String chapterPageStr, final String commandName, final CommandSource sender) {
        final List<String> lines = text.getLines();
        final List<String> chapters = text.getChapters();
        final Map<String, Integer> bookmarks = text.getBookmarks();

        //This code deals with the initial chapter.  We use this to display the initial output or contents.
        //We also use this code to display some extra information if we don't intend to use chapters
        if (pageStr == null || pageStr.isEmpty() || pageStr.matches("[0-9]+")) {
            //If an info file starts with a chapter title, list the chapters
            //If not display the text up until the first chapter.
            if (!lines.isEmpty() && lines.get(0).startsWith("#")) {
                if (onePage) {
                    return;
                }
                sender.sendMessage(tl("infoChapter"));
                final StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (final String string : chapters) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(string);
                }
                sender.sendMessage(sb.toString());
                return;
            } else {
                int page = 1;
                if (pageStr != null) {
                    try {
                        page = Integer.parseInt(pageStr);
                    } catch (final NumberFormatException ignored) {
                    }
                    if (page < 1) {
                        page = 1;
                    }
                }

                final int start = onePage ? 0 : (page - 1) * 9;
                int end;
                for (end = 0; end < lines.size(); end++) {
                    final String line = lines.get(end);
                    if (line.startsWith("#")) {
                        break;
                    }
                }

                final int pages = end / 9 + (end % 9 > 0 ? 1 : 0);
                if (page > pages) {
                    sender.sendMessage(tl("infoUnknownChapter"));
                    return;
                }
                if (!onePage && commandName != null) {
                    final StringBuilder content = new StringBuilder();
                    final String[] title = commandName.split(" ", 2);
                    if (title.length > 1) {
                        content.append(StringUtils.chop(I18n.capitalCase(title[0]).replace("", "$"))).append(" $▶ ");
                        content.append(StringUtils.chop(title[1].replace("", "$")));
                        int stepCount = -1;
                        int hexCount = 0;
                        for (int i = 0; i < content.toString().length(); i++) {
                            final char tempChar = content.toString().charAt(i);
                            if (tempChar == '$') {//Count each $
                                stepCount++;
                            }
                        }
                        for (int i = 0; i < content.toString().length(); i++) {
                            final char tempChar = content.toString().charAt(i);
                            if (tempChar == '$') {
                                //#344CEB
                                final int r1Dec = 52;
                                final int g1Dec = 76;
                                final int b1Dec = 235;

                                //#7434EB
                                final int r2Dec = 116;
                                final int g2Dec = 52;
                                final int b2Dec = 235;

                                final int rStep = (r2Dec - r1Dec) / stepCount;
                                final int gStep = (g2Dec - g1Dec) / stepCount;
                                final int bStep = (b2Dec - b1Dec) / stepCount;

                                final String rFinal = Integer.toHexString(r1Dec + (rStep * hexCount));
                                final String gFinal = Integer.toHexString(g1Dec + (gStep * hexCount));
                                final String bFinal = Integer.toHexString(b1Dec + (bStep * hexCount));

                                hexCount++;

                                content.replace(i, i+1, "§x§" + rFinal.charAt(0) + "§" + rFinal.charAt(1) + "§" + gFinal.charAt(0) + "§" + gFinal.charAt(1) + "§" + bFinal.charAt(0) + "§" + bFinal.charAt(1));
                            }
                        }
                    } else {
                        content.append(StringUtils.chop(I18n.capitalCase(commandName).replace("", "$"))); //Add $ infront of each char
                        int stepCount = -1;
                        int hexCount = 0;
                        for (int i = 0; i < content.toString().length(); i++) {
                            final char tempChar = content.toString().charAt(i);
                            if (tempChar == '$') {//Count each $
                                stepCount++;
                            }
                        }
                        for (int i = 0; i < content.toString().length(); i++) {
                            final char tempChar = content.toString().charAt(i);
                            if (tempChar == '$') {
                                //#344CEB
                                final int r1Dec = 52;
                                final int g1Dec = 76;
                                final int b1Dec = 235;

                                //#7434EB
                                final int r2Dec = 116;
                                final int g2Dec = 52;
                                final int b2Dec = 235;

                                final int rStep = (r2Dec - r1Dec) / stepCount;
                                final int gStep = (g2Dec - g1Dec) / stepCount;
                                final int bStep = (b2Dec - b1Dec) / stepCount;

                                final String rFinal = Integer.toHexString(r1Dec + (rStep * hexCount));
                                final String gFinal = Integer.toHexString(g1Dec + (gStep * hexCount));
                                final String bFinal = Integer.toHexString(b1Dec + (bStep * hexCount));

                                hexCount++;

                                content.replace(i, i+1, "§x§" + rFinal.charAt(0) + "§" + rFinal.charAt(1) + "§" + gFinal.charAt(0) + "§" + gFinal.charAt(1) + "§" + bFinal.charAt(0) + "§" + bFinal.charAt(1));
                            }
                        }
                    }
                    sender.sendMessage(tl("infoPages", page, pages, content));
                }
                for (int i = start; i < end && i < start + (onePage ? 20 : 9); i++) {
                    sender.sendMessage("§r" + lines.get(i));
                }
                if (!onePage && page < pages && commandName != null) {
                    sender.sendMessage(tl("readNextPage", commandName, page + 1));
                }
                return;
            }
        }

        //If we have a chapter, check to see if we have a page number
        int chapterpage = 0;
        if (chapterPageStr != null) {
            try {
                chapterpage = Integer.parseInt(chapterPageStr) - 1;
            } catch (final NumberFormatException ignored) {
            }
            if (chapterpage < 0) {
                chapterpage = 0;
            }
        }

        //This checks to see if we have the chapter in the index
        if (!bookmarks.containsKey(pageStr.toLowerCase(Locale.ENGLISH))) {
            sender.sendMessage(tl("infoUnknownChapter"));
            return;
        }

        //Since we have a valid chapter, count the number of lines in the chapter
        final int chapterstart = bookmarks.get(pageStr.toLowerCase(Locale.ENGLISH)) + 1;
        int chapterend;
        for (chapterend = chapterstart; chapterend < lines.size(); chapterend++) {
            final String line = lines.get(chapterend);
            if (line.length() > 0 && line.charAt(0) == '#') {
                break;
            }
        }

        //Display the chapter from the starting position
        final int start = chapterstart + (onePage ? 0 : chapterpage * 9);
        final int page = chapterpage + 1;
        final int pages = (chapterend - chapterstart) / 9 + ((chapterend - chapterstart) % 9 > 0 ? 1 : 0);
        if (!onePage && commandName != null) {
            final StringBuilder content = new StringBuilder();
            content.append(I18n.capitalCase(commandName)).append(": ");
            content.append(pageStr);
            sender.sendMessage(tl("infoChapterPages", content, page, pages));
        }
        for (int i = start; i < chapterend && i < start + (onePage ? 20 : 9); i++) {
            sender.sendMessage("§r" + lines.get(i));
        }
        if (!onePage && page < pages && commandName != null) {
            sender.sendMessage(tl("readNextPage", commandName, pageStr + " " + (page + 1)));
        }
    }
}
