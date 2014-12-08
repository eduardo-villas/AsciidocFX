package com.kodcu.controller;


import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.kodcu.bean.Config;
import com.kodcu.bean.RecentFiles;
import com.kodcu.other.Current;
import com.kodcu.other.IOHelper;
import com.kodcu.other.Item;
import com.kodcu.service.*;
import com.sun.javafx.application.HostServicesDelegate;
import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import netscape.javascript.JSObject;
import org.apache.batik.dom.svg.SAXSVGDocumentFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.XMLResourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.w3c.dom.svg.SVGDocument;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.nio.file.StandardOpenOption.*;


@Controller
public class AsciiDocController extends TextWebSocketHandler implements Initializable {

    Logger logger = LoggerFactory.getLogger(AsciiDocController.class);

    public TabPane tabPane;
    public WebView previewView;
    public SplitPane splitPane;
    public SplitPane splitPaneVertical;
    public Menu recentMenu;
    public TreeView<Item> treeView;
    public Label splitHideButton;
    public Label WorkingDirButton;
    public AnchorPane rootAnchor;

    public MenuBar recentFilesBar;
    public HBox windowHBox;
    public ProgressBar indikator;
    public Hyperlink lastConvertedFileLink;
    public ListView<String> recentListView;
    public MenuItem openFileTreeItem;
    public MenuItem openFileListItem;
    public MenuItem copyPathTreeItem;
    public MenuItem copyPathListItem;
    public MenuItem copyTreeItem;
    public MenuItem copyListItem;
    public ReentrantLock lock = new ReentrantLock();

    private Supplier<String> workindDirectorySupplier = () -> {

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select working directory");
        File file = directoryChooser.showDialog(null);

        workingDirectory = Optional.ofNullable(file.toPath().toString());

        this.workingDirectory.ifPresent(path -> {
            this.fileBrowser.browse(treeView, this, path);
        });

        return file.toPath().toString();
    };

    @Autowired
    private TablePopupController tablePopupController;

    @Autowired
    private PathResolverService pathResolver;

    @Autowired
    private RenderService renderService;

    @Autowired
    private DocBookService docBookController;

    @Autowired
    private Html5BookService htmlBookService;

    @Autowired
    private FopPdfService fopServiceRunner;

    @Autowired
    private Epub3Service epub3Service;

    CompletableFuture future = new CompletableFuture();
    ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    private Current current;

    @Autowired
    private FileBrowseService fileBrowser;

    @Autowired
    private IndikatorService indikatorService;

    @Autowired
    private KindleMobiService kindleMobiService;

    @Autowired
    private SampleBookService sampleBookService;

    private ExecutorService singleWorker = Executors.newSingleThreadExecutor();

    private ExecutorService threadPollWorker = Executors.newFixedThreadPool(4);


    private Stage stage;
    private WebEngine previewEngine;
    private StringProperty lastRendered = new SimpleStringProperty();
    private List<WebSocketSession> sessionList = new ArrayList<>();
    private Scene scene;
    private AnchorPane tableAnchor;
    private Stage tableStage;

    private Clipboard clipboard = Clipboard.getSystemClipboard();
    private Optional<Path> initialDirectory = Optional.empty();
    private ObservableList<String> recentFiles = FXCollections.observableArrayList();

    private AnchorPane configAnchor;
    private Stage configStage;

    @Autowired
    private EmbeddedWebApplicationContext server;

    private int tomcatPort = 8080;
    private HostServicesDelegate hostServices;
    private Path configPath;
    private Config config;
    private Optional<String> workingDirectory;

    List<String> bookNames = Arrays.asList("book.asc", "book.txt", "book.asciidoc", "book.adoc", "book.ad");
    private ScheduledFuture<?> scheduledFuture;
    private Timeline fiveSecondsWonder;
    private WebView mathjaxView;

    ChangeListener<String> lastRenderedChangeListener = (observableValue, old, nev) -> {
        runSingleTaskLater(task -> {
            sessionList.stream().filter(e -> e.isOpen()).forEach(e -> {
                try {
                    e.sendMessage(new TextMessage(nev));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            });
        });
    };

    @FXML
    private void createTable(Event event) {
        tableStage.show();
    }


    @FXML
    private void openConfig(ActionEvent event) {
        configStage.show();
    }


    @FXML
    private void fullScreen(ActionEvent event) {

        getStage().setFullScreen(!getStage().isFullScreen());
    }

    @FXML
    private void directoryView(ActionEvent event) {
        splitPane.setDividerPositions(0.1610294117647059, 0.5823529411764706);
    }


    @FXML
    private void generatePdf(ActionEvent event) {

        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));
        docBookController.generateDocbook(previewEngine, currentPath, false);

        runTaskLater((task) -> {
            fopServiceRunner.generateBook(currentPath, configPath);
        });
    }

    @FXML
    private void generateSampleBook(ActionEvent event) {

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select a New Directory for sample book");
        File file = directoryChooser.showDialog(null);
        runTaskLater((task) -> {
            sampleBookService.produceSampleBook(configPath, file.toPath());
            workingDirectory = Optional.of(file.toString());
            initialDirectory = Optional.of(file.toPath());
            fileBrowser.browse(treeView, this, file.toString());
            Platform.runLater(() -> {
                directoryView(null);
                addTab(file.toPath().resolve("book.asc"));
            });
        });
    }

    @FXML
    private void convertDocbook(ActionEvent event) {
        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));
//        Path currentPath = initialDirectory.map(path -> Files.isDirectory(path) ? path : path.getParent()).get();
        docBookController.generateDocbook(previewEngine, currentPath, true);

    }

    @FXML
    private void convertEpub(ActionEvent event) throws Exception {

//        Path currentPath = initialDirectory.map(path -> Files.isDirectory(path) ? path : path.getParent()).get();
        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));
        docBookController.generateDocbook(previewEngine, currentPath, false);

        runTaskLater((task) -> {
            epub3Service.produceEpub3(currentPath, configPath);
        });
    }

    public synchronized String appendFormula(String fileName, String formula) {

        if (fileName.endsWith(".png")) {
            WebEngine engine = mathjaxView.getEngine();
            engine.executeScript(String.format("appendFormula('%s','%s')", fileName, IOHelper.normalize(formula)));
            return "/images/" + fileName;
        }

        return "";

    }

    public synchronized void svgToPng(String fileName, String svg, String formula) {

        if (!fileName.endsWith(".png") || !svg.startsWith("<svg"))
            return;

        Integer cacheHit = current.getCache().get(fileName);
        int hashCode = fileName.concat(formula).hashCode();
        if (Objects.nonNull(cacheHit))
            if (hashCode == cacheHit)
                return;

        current.getCache().put(fileName, hashCode);

        runSingleTaskLater(task -> {
            try {
                StringReader reader = new StringReader(svg);
                String uri = "http://www.w3.org/2000/svg";
                String parser = XMLResourceDescriptor.getXMLParserClassName();
                SAXSVGDocumentFactory f = new SAXSVGDocumentFactory(parser);
                SVGDocument doc = f.createSVGDocument(uri, reader);

                TranscoderInput transcoderInput = new TranscoderInput(doc);
                ByteArrayOutputStream ostream = new ByteArrayOutputStream();
                TranscoderOutput transcoderOutput = new TranscoderOutput(ostream);

                PNGTranscoder transcoder = new PNGTranscoder();
                transcoder.transcode(transcoderInput, transcoderOutput);
                ostream.flush();
                ostream.close();

                Path path = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));
                Files.createDirectories(path.resolve("images"));

                Files.write(path.resolve("images/").resolve(fileName), ostream.toByteArray(), CREATE, WRITE, TRUNCATE_EXISTING);

                lastRenderedChangeListener.changed(null, lastRendered.getValue(), lastRendered.getValue());


            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }

    public <T> void runTaskLater(Consumer<Task<T>> consumer) {

        Task<T> task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                consumer.accept(this);
                return null;
            }
        };

        threadPollWorker.submit(task);
    }

    public <T> void runSingleTaskLater(Consumer<Task<T>> consumer) {

        Task<T> task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                consumer.accept(this);
                return null;
            }
        };

        singleWorker.submit(task);
    }

    @FXML
    private void convertMobi(ActionEvent event) throws Exception {


        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));

        if (Objects.nonNull(config.getKindlegenDir())) {
            if (!Files.exists(Paths.get(config.getKindlegenDir()))) {
                config.setKindlegenDir(null);
            }
        }

        if (Objects.isNull(config.getKindlegenDir())) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select 'kindlegen' File");
            File kindlegenFile = fileChooser.showOpenDialog(null);
            if (Objects.isNull(kindlegenFile))
                return;

            config.setKindlegenDir(kindlegenFile.toPath().getParent().toString());

        }

        runTaskLater((task) -> {
            epub3Service.produceEpub3(currentPath, configPath);
            kindleMobiService.produceMobi(currentPath, config.getKindlegenDir());
        });

    }

    @FXML
    private void generateHtml(ActionEvent event) {

        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));

        htmlBookService.produceXhtml5(previewEngine, currentPath, configPath);
    }


    @FXML
    private void maximize(Event event) {

        // Change stage properties
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();

        if (bounds.getHeight() == stage.getHeight() && bounds.getWidth() == stage.getWidth()) {
            stage.setX(50);
            stage.setY(50);
            stage.setWidth(bounds.getWidth() * 0.8);
            stage.setHeight(bounds.getHeight() * 0.8);
        } else {
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
        }
    }

    @FXML
    private void minimize(ActionEvent event) {
        getStage().setIconified(true);
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        try {
            CodeSource codeSource = AsciiDocController.class.getProtectionDomain().getCodeSource();
            File jarFile = new File(codeSource.getLocation().toURI().getPath());
            configPath = jarFile.toPath().getParent().getParent().resolve("conf");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        loadConfigurations();
        loadRecentFileList();

        recentListView.setItems(recentFiles);
        recentFiles.addListener((ListChangeListener<String>) c -> {
            recentListView.visibleProperty().setValue(c.getList().size() > 0);
            recentListView.getSelectionModel().selectFirst();
        });

        recentListView.setOnMouseClicked(event -> {
            if (event.getClickCount() > 1) {
                openRecentListFile(event);
            }
        });

        tomcatPort = server.getEmbeddedServletContainer().getPort();

        lastRendered.addListener(lastRenderedChangeListener);

        // MathJax
        mathjaxView = new WebView();
        mathjaxView.setVisible(false);
        rootAnchor.getChildren().add(mathjaxView);

        WebEngine mathjaxEngine = mathjaxView.getEngine();
        mathjaxEngine.getLoadWorker().stateProperty().addListener((observableValue1, state, state2) -> {
            if (state2 == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) mathjaxEngine.executeScript("window");
                if (Objects.isNull(window.getMember("app"))) ;
                window.setMember("app", this);
            }
        });
        //

        mathjaxView.getEngine().load(String.format("http://localhost:%d/mathjax.html", tomcatPort));


        previewEngine = previewView.getEngine();
        previewEngine.load(String.format("http://localhost:%d/index.html", tomcatPort));

        previewEngine.getLoadWorker().stateProperty().addListener((observableValue1, state, state2) -> {
            if (state2 == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) previewEngine.executeScript("window");
                if (Objects.isNull(window.getMember("app"))) ;
                window.setMember("app", this);
            }
        });

        previewEngine.getLoadWorker().exceptionProperty().addListener((ov, t, t1) -> {
            t1.printStackTrace();
        });


        /// Treeview

        workingDirectory = Optional.ofNullable(config.getWorkingDirectory());

        String workDir = workingDirectory.orElse(System.getProperty("user.home"));
//
        fileBrowser.browse(treeView, this, workDir);

        //

        AwesomeDude.setIcon(WorkingDirButton, AwesomeIcon.FOLDER_ALT, "14.0");
        AwesomeDude.setIcon(splitHideButton, AwesomeIcon.CHEVRON_LEFT, "14.0");

        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            if (tabPane.getTabs().isEmpty())
                runActionLater(this::newDoc);
        });

        openFileTreeItem.setOnAction(event -> {
            Path path = getSelectedTabPath();
            if (!Files.isDirectory(path))
                this.addTab(path);
        });

        openFileListItem.setOnAction(this::openRecentListFile);

        copyPathTreeItem.setOnAction(event -> {
            Path path = getSelectedTabPath();
            this.cutCopy(path.toString());
        });

        copyPathListItem.setOnAction(event -> {
            this.cutCopy(recentListView.getSelectionModel().getSelectedItem());
        });

        copyTreeItem.setOnAction(event -> {
            Path path = getSelectedTabPath();
            this.copyFile(path);
        });

        copyListItem.setOnAction(event -> {
            Path path = Paths.get(recentListView.getSelectionModel().getSelectedItem());
            this.copyFile(path);
        });

        treeView.setOnMouseClicked(event -> {
            TreeItem<Item> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (Objects.isNull(selectedItem))
                return;
            Path selectedPath = selectedItem.getValue().getPath();
            if (event.getButton() == MouseButton.PRIMARY)
                if (Files.isDirectory(selectedPath)) {
                    try {
                        if (selectedItem.getChildren().size() == 0)
                            Files.newDirectoryStream(selectedPath).forEach(path -> {
                                if (pathResolver.isHidden(path))
                                    return;

                                if (Files.isDirectory(path) || pathResolver.isAsciidoc(path))
                                    selectedItem.getChildren().add(new TreeItem<>(new Item(path)));
                            });
                        selectedItem.setExpanded(!selectedItem.isExpanded());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (event.getClickCount() > 1) {
                    this.addTab(selectedPath);
                }
        });

        runActionLater(this::newDoc);

    }

    private void openRecentListFile(Event event) {
        Path path = Paths.get(recentListView.getSelectionModel().getSelectedItem());

        if (pathResolver.isAsciidoc(path))
            addTab(path);
        else
            getHostServices().showDocument(path.toUri().toString());
    }

    private Path getSelectedTabPath() {
        TreeItem<Item> selectedItem = treeView.getSelectionModel().getSelectedItem();
        Item value = selectedItem.getValue();
        Path path = value.getPath();
        return path;
    }

    private void runActionLater(Consumer<ActionEvent> consumer) {
        Platform.runLater(() -> {
            consumer.accept(null);
        });
    }

    private void loadConfigurations() {
        try {
            YamlReader yamlReader =
                    new YamlReader(new FileReader(configPath.resolve("config.yml").toFile()));
            yamlReader.getConfig().setClassTag("Config", Config.class);
            config = yamlReader.read(Config.class);

        } catch (YamlException | FileNotFoundException e) {
            e.printStackTrace();
        }

        if (!config.getDirectoryPanel())
            Platform.runLater(() -> {
                splitPane.setDividerPositions(0, 0.55);
            });

    }

    private void loadRecentFileList() {

        try {
            YamlReader yamlReader =
                    new YamlReader(new FileReader(configPath.resolve("recentFiles.yml").toFile()));
            yamlReader.getConfig().setClassTag("RecentFiles", RecentFiles.class);
            RecentFiles readed = yamlReader.read(RecentFiles.class);

            recentFiles.addAll(readed.getFiles());
        } catch (YamlException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void externalBrowse() {

        hostServices.showDocument(String.format("http://localhost:%d/index.html", tomcatPort));
    }

    @FXML
    public void changeWorkingDir(Event actionEvent) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        initialDirectory.ifPresent(path -> {
            if (Files.isDirectory(path))
                directoryChooser.setInitialDirectory(path.toFile());
            else
                directoryChooser.setInitialDirectory(path.getParent().toFile());
        });
        directoryChooser.setTitle("Select Working Directory");
        File selectedDir = directoryChooser.showDialog(null);
        if (Objects.nonNull(selectedDir)) {
            config.setWorkingDirectory(selectedDir.toString());
            workingDirectory = Optional.of(selectedDir.toString());
            initialDirectory = Optional.of(selectedDir.toPath());
            fileBrowser.browse(treeView, this, selectedDir.toString());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessionList.add(session);
        String value = lastRendered.getValue();
        if (Objects.nonNull(value))
            session.sendMessage(new TextMessage(value));

    }

    @FXML
    public void closeApp(ActionEvent event) throws IOException {

        File recentFileYml = configPath.resolve("recentFiles.yml").toFile();
        YamlWriter yamlWriter = new YamlWriter(new FileWriter(recentFileYml));
        yamlWriter.getConfig().setClassTag("RecentFiles", RecentFiles.class);
        yamlWriter.write(new RecentFiles(recentFiles));
        yamlWriter.close();

        //

        File configYml = configPath.resolve("config.yml").toFile();
        yamlWriter = new YamlWriter(new FileWriter(configYml));
        yamlWriter.getConfig().setClassTag("Config", Config.class);
        yamlWriter.write(config);
        yamlWriter.close();

    }

    @FXML
    private void openDoc(Event event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Asciidoc", "*.asc", "*.asciidoc", "*.adoc", "*.ad", "*.txt"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All", "*.*"));
        initialDirectory.ifPresent(e -> {
            if (Files.isDirectory(e))
                fileChooser.setInitialDirectory(e.toFile());
            else
                fileChooser.setInitialDirectory(e.getParent().toFile());
        });
        List<File> chosenFiles = fileChooser.showOpenMultipleDialog(stage);
        if (chosenFiles != null) {
            initialDirectory = Optional.of(chosenFiles.get(0).toPath());
            chosenFiles.stream().map(e -> e.toPath()).forEach(this::addTab);
            chosenFiles.stream()
                    .map(File::toString).filter(file -> !recentFiles.contains(file))
                    .forEach(recentFiles::addAll);
        }

    }

    @FXML
    private void recentFileList(Event event) {
        List<MenuItem> menuItems = recentFiles.stream().map(path -> Paths.get(path)).filter(path -> !Files.isDirectory(path)).map(path -> {
            MenuItem menuItem = new MenuItem();
            menuItem.setText(path.toAbsolutePath().toString());
            menuItem.setOnAction(actionEvent -> {
                addTab(path);
            });
            return menuItem;
        }).limit(config.getRecentFileListSize()).collect(Collectors.toList());

        recentMenu.getItems().clear();
        recentMenu.getItems().addAll(menuItems);

    }

    @FXML
    public void newDoc(Event event) {

        WebView webView = createWebView();
        AnchorPane anchorPane = new AnchorPane();
        Node editorVBox = createEditorVBox(webView);
        fitToParent(editorVBox);
        anchorPane.getChildren().add(editorVBox);
        Tab tab = createTab();
        tab.setContent(anchorPane);
        tab.selectedProperty().addListener((observableValue, before, after) -> {
            if (after) {
                current.putTab(tab, current.getNewTabPaths().get(tab), webView);
                WebEngine webEngine = webView.getEngine();

                Worker.State state = webEngine.getLoadWorker().getState();
                if (state == Worker.State.SUCCEEDED)
                    webEngine.executeScript("waitForGetValue()");
            }
        });
        ((Label) tab.getGraphic()).setText("new *");
        current.putTab(tab, null, webView);
        tabPane.getTabs().add(tab);

    }

    private Node createEditorVBox(WebView webView) {
        MenuBar menuBar = new MenuBar();
        menuBar.getStyleClass().add("editorToolsBar");
        String iconSize = "14.0";

        Label saveLabel = AwesomeDude.createIconLabel(AwesomeIcon.SAVE, iconSize);
        Label newLabel = AwesomeDude.createIconLabel(AwesomeIcon.FILE_TEXT_ALT, iconSize);
        Label openLabel = AwesomeDude.createIconLabel(AwesomeIcon.FOLDER_ALTPEN_ALT, iconSize);
        Label boldLabel = AwesomeDude.createIconLabel(AwesomeIcon.BOLD, iconSize);
        Label italicLabel = AwesomeDude.createIconLabel(AwesomeIcon.ITALIC, iconSize);
        Label headerLabel = AwesomeDude.createIconLabel(AwesomeIcon.HEADER, iconSize);
        Label codeLabel = AwesomeDude.createIconLabel(AwesomeIcon.CODE, iconSize);
        Label ulListLabel = AwesomeDude.createIconLabel(AwesomeIcon.LIST_UL, iconSize);
        Label olListLabel = AwesomeDude.createIconLabel(AwesomeIcon.LIST_ALTL, iconSize);
        Label tableLabel = AwesomeDude.createIconLabel(AwesomeIcon.TABLE, iconSize);
        Label imageLabel = AwesomeDude.createIconLabel(AwesomeIcon.IMAGE, iconSize);
        Label subscriptLabel = AwesomeDude.createIconLabel(AwesomeIcon.SUBSCRIPT, iconSize);
        Label superScriptLabel = AwesomeDude.createIconLabel(AwesomeIcon.SUPERSCRIPT, iconSize);


        // Events
        newLabel.setOnMouseClicked(this::newDoc);
        openLabel.setOnMouseClicked(this::openDoc);
        saveLabel.setOnMouseClicked(this::saveDoc);
        boldLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("boldText()");
        });
        italicLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("italicizeText()");
        });

        codeLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("addSourceCode()");
        });

        tableLabel.setOnMouseClicked(this::createTable);

        subscriptLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("subScript()");
        });

        superScriptLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("superScript()");
        });

        imageLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("addImageSection()");
        });

        headerLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("addHeading()");
        });

        ulListLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("addUlList()");
        });

        olListLabel.setOnMouseClicked(event -> {
            current.currentEngine().executeScript("addOlList()");
        });

//        ColorPicker colorPicker=new ColorPicker(Color.BLACK);

        menuBar.getMenus().addAll(
                new Menu("", newLabel),
                new Menu("", openLabel),
                new Menu("", saveLabel),
                new Menu("", boldLabel),
                new Menu("", italicLabel),
                new Menu("", headerLabel),
                new Menu("", codeLabel),
                new Menu("", ulListLabel),
                new Menu("", olListLabel),
                new Menu("", tableLabel),
                new Menu("", imageLabel),
                new Menu("", subscriptLabel),
                new Menu("", superScriptLabel)
        );

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(webView);
        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        return new VBox(menuBar, scrollPane);
    }

    public void addTab(Path path) {

        AnchorPane anchorPane = new AnchorPane();
        WebView webView = createWebView();
        WebEngine webEngine = webView.getEngine();
        webEngine.getLoadWorker().stateProperty().addListener((observableValue1, state, state2) -> {
            if (state2 == Worker.State.SUCCEEDED) {
                webEngine.executeScript(String.format("waitForSetValue('%s')", IOHelper.normalize(IOHelper.readFile(path))));
            }
        });

        Node editorVBox = createEditorVBox(webView);
        fitToParent(editorVBox);

        anchorPane.getChildren().add(editorVBox);

        Tab tab = createTab();
        ((Label) tab.getGraphic()).setText(path.getFileName().toString());
        tab.setContent(anchorPane);

        tab.selectedProperty().addListener((observableValue, before, after) -> {
            if (after) {
                current.putTab(tab, path, webView);
                webEngine.executeScript("if((typeof waitForGetValue)!='undefined') waitForGetValue()");
            }
        });

        current.putTab(tab, path, webView);
        tabPane.getTabs().add(tab);

        Tab lastTab = tabPane.getTabs().get(tabPane.getTabs().size() - 1);
        tabPane.getSelectionModel().select(lastTab);

        recentFiles.remove(path.toString());
        recentFiles.add(0, path.toString());

    }

    @FXML
    public void hideLeftSplit(Event event) {
        splitPane.setDividerPositions(0, 0.55);
    }

    private Tab createTab() {
        Tab tab = new Tab();

        MenuItem menuItem0 = new MenuItem("Close");
        menuItem0.setOnAction(actionEvent -> {
            tabPane.getTabs().remove(current.getCurrentTab());
        });

        MenuItem menuItem1 = new MenuItem("Close All");
        menuItem1.setOnAction(actionEvent -> {
            tabPane.getTabs().clear();
        });
        MenuItem menuItem2 = new MenuItem("Close Others");
        menuItem2.setOnAction(actionEvent -> {
            List<Tab> blackList = new ArrayList<>();
            blackList.addAll(tabPane.getTabs());
            blackList.remove(tab);
            tabPane.getTabs().removeAll(blackList);
        });

        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(menuItem0, menuItem1, menuItem2);

        tab.contextMenuProperty().setValue(contextMenu);
        Label label = new Label();

        label.setOnMouseClicked(mouseEvent -> {

            if (mouseEvent.getClickCount() > 1) {
                if (splitPane.getDividerPositions()[0] > 0.1)
                    splitPane.setDividerPositions(0, 1);
                else
                    splitPane.setDividerPositions(0.18, 0.60);

            }
        });

        tab.setGraphic(label);


        return tab;
    }


    private WebView createWebView() {

        WebView webView = new WebView();


        WebEngine webEngine = webView.getEngine();

        webEngine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                if (Objects.isNull(window.getMember("app"))) ;
                window.setMember("app", this);

            }
        });
        webEngine.load(String.format("http://localhost:%d/editor.html", tomcatPort));
        return webView;
    }

    public void onscroll(Object pos, Object max) {
        if (Objects.isNull(pos) || Objects.isNull(max))
            return;

        Number position = (Number) pos; // current scroll position for editor
        Number maximum = (Number) max; // max scroll position for editor

        double ratio = (position.doubleValue() * 100) / maximum.doubleValue();
        Integer browserMaxScroll = (Integer) previewEngine.executeScript("document.documentElement.scrollHeight - document.documentElement.clientHeight;");
        double browserScrollOffset = (Double.valueOf(browserMaxScroll) * ratio) / 100.0;
        previewEngine.executeScript(String.format("window.scrollTo(0, %f )", browserScrollOffset));

    }

    public void scrollToCurrentLine(String text) {

        //if ("".equals(text))
        //    return;
        //normalize apostrophe if presents otherwise the inevitable exception comes up
        text = IOHelper.normalize(text);

        String format = String.format("runScroller('%s')", text);
        try {
            previewEngine.executeScript(format);
        } catch (Exception e) {

        }
    }

    @RequestMapping(value = {"**.asciidoc", "**.asc", "**.txt", "**.ad", "**.adoc"}, method = RequestMethod.GET)
    @ResponseBody
    public DeferredResult<String> asciidoc(HttpServletRequest request) {

        DeferredResult<String> deferredResult = new DeferredResult<String>();

        String uri = request.getRequestURI();

        if (uri.startsWith("/"))
            uri = uri.substring(1);

        if (Objects.nonNull(current.currentPath())) {
            Path ascFile = current.currentParentRoot().resolve(uri);

            Platform.runLater(() -> {
                this.addTab(ascFile);
            });

            deferredResult.setResult("OK");
        }

        return deferredResult;
    }

    @RequestMapping(value = {"/**/{extension:(?:\\w|\\W)+\\.(?:jpg|bmp|gif|jpeg|png|webp)$}"}, method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<byte[]> images(HttpServletRequest request, @PathVariable("extension") String extension) {

        Enumeration<String> headerNames = request.getHeaderNames();
        String uri = request.getRequestURI();
        byte[] temp = new byte[]{};
        if (uri.startsWith("/"))
            uri = uri.substring(1);

        Path imageFile = null;
        if (Objects.nonNull(current.currentPath()))
            imageFile = current.currentParentRoot().resolve(uri);
        else
            imageFile = workingDirectory.map(Paths::get).get().resolve(uri);

        try {
            temp = Files.readAllBytes(imageFile);
        } catch (Exception ex) {
            logger.debug(ex.getMessage(), ex);
        }


        return new ResponseEntity<>(temp, HttpStatus.OK);
    }

    public String normalize(String content) {
        return IOHelper.normalize(content);
    }

    public String plantUml(String uml, String type, String fileName) throws IOException {
        Objects.requireNonNull(fileName);

        if (!fileName.endsWith(".png"))
            return "";

        if (!uml.contains("@startuml") && !uml.contains("@enduml"))
            uml = "@startuml\n" + uml + "\n@enduml";

        SourceStringReader reader = new SourceStringReader(uml);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {

            if ("ascii".equalsIgnoreCase(type)) {
                String desc = reader.generateImage(os, new FileFormatOption(FileFormat.ATXT));

                return os.toString("UTF-8");
            }
            // default: png
            else {

                Path path = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));
                Path umlPath = path.resolve("images/").resolve(fileName);

                Integer cacheHit = current.getCache().get(fileName);

                int hashCode = (fileName + type + uml).hashCode();
                if (Objects.isNull(cacheHit) || hashCode != cacheHit) {

                    runTaskLater(task -> {
                        try {
                            String desc = reader.generateImage(os, new FileFormatOption(FileFormat.PNG));

                            Files.createDirectories(path.resolve("images"));

                            IOHelper.writeToFile(umlPath, os.toByteArray(), CREATE, WRITE, TRUNCATE_EXISTING);

                            lastRenderedChangeListener.changed(null, lastRendered.getValue(), lastRendered.getValue());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }


                current.getCache().put(fileName, hashCode);

                String umlRelativePath = Paths.get("images") + "/" + umlPath.getFileName();

                return umlRelativePath;
            }

        }

    }

    public void appendWildcard() {
        Label label = (Label) current.getCurrentTab().getGraphic();

        if (!label.getText().contains(" *"))
            label.setText(label.getText() + " *");
    }

    public void textListener(String text) {

        runActionLater(run -> {
            String rendered = renderService.convertBasicHtml(previewEngine, text);

            runSingleTaskLater(task -> {
                if (Objects.nonNull(rendered))
                    lastRendered.setValue(rendered);
            });
        });

    }

    public void htmlOnePage() {

        if (bookNames.contains(current.getCurrentTabText())) {
            generateHtml(null);
            return;
        }

        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));

        String asciidoc = current.currentEditorValue();

        String html = renderService.convertHtmlArticle(previewEngine, IOHelper.normalize(asciidoc));

        runTaskLater(task -> {
            indikatorService.startCycle();
            String tabText = current.getCurrentTabText().replace("*", "").trim();

            Path path = currentPath.resolve(tabText.concat(".html"));
            IOHelper.writeToFile(path, html, CREATE, TRUNCATE_EXISTING, WRITE);
            recentFiles.add(0, path.toString());

            indikatorService.hideIndikator();

        });

    }

    public void cutCopy(String data) {
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(data);
        clipboard.setContent(clipboardContent);
    }

    public void pdfOnePage() {

        if (bookNames.contains(current.getCurrentTabText())) {
            generatePdf(null);
            return;
        }

        Path currentPath = Paths.get(workingDirectory.orElseGet(workindDirectorySupplier));

        String docbook = docBookController.generateDocbookArticle(previewEngine, currentPath);

        runTaskLater(task -> {
            fopServiceRunner.generateArticle(currentPath, configPath, docbook);
        });

    }

    public void copyFile(Path path) {
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putFiles(Arrays.asList(path.toFile()));
        clipboard.setContent(clipboardContent);
    }


    public String paste() {
        return clipboard.getString();
    }

    public void saveDoc() {
        saveDoc(null);
    }

    @FXML
    public void saveDoc(Event actionEvent) {
        Path currentPath = current.currentPath();
        if (currentPath == null) {
            FileChooser chooser = new FileChooser();
            workingDirectory.ifPresent(path -> {
                chooser.setInitialDirectory(Paths.get(path).toFile());
            });
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Asciidoc", "*.asc", "*.asciidoc", "*.adoc", "*.ad", "*.txt"));
            File file = chooser.showSaveDialog(null);
            if (file == null)
                return;
            IOHelper.writeToFile(file, (String) current.currentEngine().executeScript("editor.getValue();"), TRUNCATE_EXISTING, CREATE);
            current.putTab(current.getCurrentTab(), file.toPath(), current.currentView());
            current.setCurrentTabText(file.toPath().getFileName().toString());
            recentFiles.remove(file.toString());
            recentFiles.add(0, file.toString());
        } else {
            IOHelper.writeToFile(currentPath.toFile(), (String) current.currentEngine().executeScript("editor.getValue();"), TRUNCATE_EXISTING, CREATE);
        }

        Label label = (Label) current.getCurrentTab().getGraphic();
        label.setText(label.getText().replace(" *", ""));
    }

    private void fitToParent(Node node) {
        AnchorPane.setTopAnchor(node, 0.0);
        AnchorPane.setBottomAnchor(node, 0.0);
        AnchorPane.setLeftAnchor(node, 0.0);
        AnchorPane.setRightAnchor(node, 0.0);
    }

    public void saveAndCloseCurrentTab() {
        this.saveDoc();
        tabPane.getTabs().remove(current.getCurrentTab());
    }

    public ProgressIndicator getIndikator() {
        return indikator;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Stage getStage() {
        return stage;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public Scene getScene() {
        return scene;
    }

    public void setTableAnchor(AnchorPane tableAnchor) {
        this.tableAnchor = tableAnchor;
    }

    public AnchorPane getTableAnchor() {
        return tableAnchor;
    }

    public void setTableStage(Stage tableStage) {
        this.tableStage = tableStage;
    }

    public Stage getTableStage() {
        return tableStage;
    }

    public void setConfigAnchor(AnchorPane configAnchor) {
        this.configAnchor = configAnchor;
    }

    public AnchorPane getConfigAnchor() {
        return configAnchor;
    }

    public void setConfigStage(Stage configStage) {
        this.configStage = configStage;
    }

    public Stage getConfigStage() {
        return configStage;
    }

    public SplitPane getSplitPane() {
        return splitPane;
    }

    public TreeView<Item> getTreeView() {
        return treeView;
    }

    public void setHostServices(HostServicesDelegate hostServices) {
        this.hostServices = hostServices;
    }

    public HostServicesDelegate getHostServices() {
        return hostServices;
    }

    public Optional<Path> getInitialDirectory() {
        return initialDirectory;
    }

    public Config getConfig() {
        return config;
    }

    public TablePopupController getTablePopupController() {
        return tablePopupController;
    }

    public StringProperty getLastRendered() {
        return lastRendered;
    }

    public ObservableList<String> getRecentFiles() {
        return recentFiles;
    }
}
