package io.github.md2conf.converter.md2wiki;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.jira.converter.JiraConverterExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import io.github.md2conf.converter.AttachmentUtil;
import io.github.md2conf.converter.PageStructureConverter;
import io.github.md2conf.flexmart.ext.confluence.macros.ConfluenceMacroExtension;
import io.github.md2conf.flexmart.ext.crosspage.links.CrosspageLinkExtension;
import io.github.md2conf.flexmart.ext.curly.braced.escaper.CurlyBracedBlockExtension;
import io.github.md2conf.flexmart.ext.fenced.code.block.CustomFencedCodeBlockExtension;
import io.github.md2conf.flexmart.ext.local.attachments.LocalAttachmentLinkExtension;
import io.github.md2conf.flexmart.ext.local.image.LocalImageExtension;
import io.github.md2conf.flexmart.ext.plantuml.code.macro.PlantUmlCodeMacroExtension;
import io.github.md2conf.indexer.Page;
import io.github.md2conf.indexer.PagesStructure;
import io.github.md2conf.model.ConfluenceContentModel;
import io.github.md2conf.model.ConfluencePage;
import io.github.md2conf.title.processor.PageStructureTitleProcessor;
import io.github.md2conf.title.processor.WikiTitleRemover;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.vladsch.flexmark.html.HtmlRenderer.SUPPRESS_HTML_COMMENT_BLOCKS;
import static com.vladsch.flexmark.html.HtmlRenderer.SUPPRESS_INLINE_HTML_COMMENTS;
import static io.github.md2conf.converter.md2wiki.attachment.LocalPathUtil.collectLocalAttachmentPaths;
import static io.github.md2conf.converter.md2wiki.attachment.LocalPathUtil.collectLocalImagePaths;

public class Md2WikiConverter implements PageStructureConverter {

    private final PageStructureTitleProcessor pagesStructureTitleProcessor;
    private final Path outputPath;
    private final boolean needToRemoveTitle;
    private final boolean plantumlMacro;
    private final String plantumlCodeMacroName;


    public Md2WikiConverter(PageStructureTitleProcessor pagesStructureTitleProcessor,
                            Path outputPath, boolean needToRemoveTitle, boolean plantumlMacro, String plantumlCodeMacroName) {
        this.pagesStructureTitleProcessor = pagesStructureTitleProcessor;
        this.outputPath = outputPath;
        this.needToRemoveTitle = needToRemoveTitle;
        this.plantumlMacro = plantumlMacro;
        this.plantumlCodeMacroName = plantumlCodeMacroName;
    }

    private MutableDataSet flexmarkOptions() {
        List<Extension> extensions = new ArrayList<>();
        MutableDataSet res =  new MutableDataSet().set(Parser.EXTENSIONS, extensions);
        extensions.add(TablesExtension.create());
        extensions.add(StrikethroughExtension.create());
        extensions.add(LocalAttachmentLinkExtension.create());
        extensions.add(LocalImageExtension.create());
        extensions.add(CrosspageLinkExtension.create());
        extensions.add(CurlyBracedBlockExtension.create());
        extensions.add(ConfluenceMacroExtension.create());
        extensions.add(JiraConverterExtension.create());
        if (plantumlMacro) {
            extensions.add(PlantUmlCodeMacroExtension.create());
            res.set(PlantUmlCodeMacroExtension.CONFLUENCE_PLANTUML_MACRO, plantumlCodeMacroName);
        }
        extensions.add(CustomFencedCodeBlockExtension.create());
        res.set(SUPPRESS_HTML_COMMENT_BLOCKS, true);
        res.set(SUPPRESS_INLINE_HTML_COMMENTS, true);
        return res;
    }

    @Override
    public ConfluenceContentModel convert(PagesStructure pagesStructure) throws IOException {
        Map<Path, String> titleMap = pagesStructureTitleProcessor.toTitleMap(pagesStructure);
        List<ConfluencePage> confluencePages = new ArrayList<>();
        for (Page topLevelPage : pagesStructure.pages()) { //use "for" loop to throw exception to caller
            ConfluencePage confluencePage;
            confluencePage = convertAndCreateConfluencePage(topLevelPage, Paths.get(""), titleMap);
            confluencePages.add(confluencePage);
        }
        return new ConfluenceContentModel(confluencePages);
    }

    /**
     * @param page         - a Page
     * @param relativePart - relative path to target path, used to process children recursively
     * @param titleMap     -  title Map
     * @return ConfluencePage
     */
    private ConfluencePage convertAndCreateConfluencePage(Page page, Path relativePart, Map<Path, String> titleMap) throws IOException {

        //read markdown file from Page path
        String markdown = FileUtils.readFileToString(page.path().toFile(), Charset.defaultCharset()); //todo extract charset as parameter

        //Convert to wiki using FlexMark parser and renderer
        DataHolder flexmarkOptions = flexmarkOptions()
                .set(LocalAttachmentLinkExtension.CURRENT_FILE_PATH, page.path().getParent())
                .set(LocalImageExtension.CURRENT_FILE_PATH, page.path().getParent())
                .set(CrosspageLinkExtension.CURRENT_FILE_PATH, page.path().getParent())
                .set(CrosspageLinkExtension.TITLE_MAP, titleMap)
                .toImmutable();
        Parser parser = Parser.builder(flexmarkOptions).build();
        HtmlRenderer renderer = HtmlRenderer.builder(flexmarkOptions).build();
        Node document = parser.parse(markdown);
        String wiki = renderer.render(document);

        //collect attachments from local images and local file links
        List<Path> imagePaths = collectLocalImagePaths(document);
        List<Path> localAttachmentPaths = collectLocalAttachmentPaths(document);

        //calculate output file names
        String targetFileName = FilenameUtils.getBaseName(page.path().toString()) + ".wiki";
        Path targetPath = outputPath.resolve(relativePart).resolve(targetFileName);

        //copy converted content and attachments
        FileUtils.writeStringToFile(targetPath.toFile(), wiki, Charset.defaultCharset());
        Set<Path> copiedAttachments = AttachmentUtil.copyPageAttachments(targetPath, page.attachments(), imagePaths, localAttachmentPaths);

        // create ConfluencePage model
        ConfluencePage result = new ConfluencePage();
        result.setContentFilePath(targetPath.toString());
        result.setTitle(titleMap.get(page.path().toAbsolutePath()));
        result.setAttachments(AttachmentUtil.toAttachmentsMap(copiedAttachments));
        result.setType(ConfluenceContentModel.Type.WIKI);
        result.setAttachments(AttachmentUtil.toAttachmentsMap(copiedAttachments));
        result.setSkipUpdate(page.skipUpdate());
        // process children
        if (page.children() != null && !page.children().isEmpty()) {
            String childrenDirAsStr = FilenameUtils.concat(
                    relativePart.toString(),
                    FilenameUtils.removeExtension(targetPath.getFileName().toString()));
            Path childrenDir = outputPath.resolve(childrenDirAsStr);
            FileUtils.forceMkdir(childrenDir.toFile());
            for (Page childPage : page.children()) {
                result.getChildren().add(convertAndCreateConfluencePage(childPage, outputPath.relativize(childrenDir), titleMap));
            }
        }
        if (needToRemoveTitle) {
            WikiTitleRemover.removeTitle(targetPath);
        }
        return result;
    }

    @Override
    public String toString() {
        return "Md2WikiConverter";
    }
}
