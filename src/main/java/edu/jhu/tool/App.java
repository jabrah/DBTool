package edu.jhu.tool;

import com.google.inject.Guice;
import com.google.inject.Injector;
import edu.jhu.tool.config.AppConfig;
import edu.jhu.tool.config.AppModule;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class App  {
    private static final String GRAVITY_VERSO = "east";
    private static final String GRAVITY_RECTO = "west";

    private class DropboxFile {
        String name;
        String url;

        boolean isImage() {
            return name.endsWith(".tif");
        }
        boolean isXLS() {
            return name.endsWith(".xls");
        }

        @Override
        public String toString() {
            return "DropboxFile{" +
                    "name='" + name + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }

    private class DropboxImageFile extends DropboxFile {
        String callNumber;
        String title;
        String publicationDate;
        String[] pageNumbers;
        int sortOrder;

        @Override
        public String toString() {
            return "DropboxImageFile{" +
                    "name='" + name + '\'' +
                    ", url='" + url + '\'' +
                    ", callNumber='" + callNumber + '\'' +
                    ", title='" + title + '\'' +
                    ", publicationDate='" + publicationDate + '\'' +
                    ", pageNumbers=" + Arrays.toString(pageNumbers) +
                    ", sortOrder=" + sortOrder +
                    '}';
        }
    }

    private class DownloadRunnable implements Runnable {

        private DropboxFile file;
        private String outputFile;

        DownloadRunnable(DropboxFile file, String outputFile) {
            this.file = file;
            this.outputFile = outputFile;
        }

        @Override
        public void run() {
            download(file.name, file.url);
        }

        private void download(String filename, String url) {
            // Don't download anything if the file already exists!
            Path outPath = Paths.get(outputFile);
            if (Files.exists(outPath)) {
                System.out.println("File [" + filename + "] already exists.");
                return;
            }

            System.out.println("Downloading [" + filename + "] from [" + url
                    + "] to path [" + outputFile + "]");

            int timeouts = 30000;
            try {
                FileUtils.copyURLToFile(new URL(url), outPath.toFile(), timeouts, timeouts);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class ImageSplitterRunnable implements Runnable {

        private Path original;
        private Path output;
        private String gravity;

        ImageSplitterRunnable(String gravity, Path original, Path output) {
            this.original = original;
            this.output = output;
            this.gravity = gravity;
        }

        @Override
        public void run() {
            String command = "convert -crop 55%x100% -gravity " + gravity
                    + " " + original.toString() + " +repage " + output.toString();

            boolean result = false;
            System.out.println(command);
            try {
                Process p = Runtime.getRuntime().exec(command);
                result = p.waitFor(30, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                System.err.println("Command failed.\n  " + command);
            }

            if (!result) {
                System.err.println("Command timed out!\n  " + command);
            }
        }
    }

    private AppConfig config;

    public App(AppConfig config) {
        this.config = config;
    }

    public static void main( String[] args ) throws Exception {
        Injector injector = Guice.createInjector(new AppModule());
        AppConfig config = injector.getInstance(AppConfig.class);

        App app = new App(config);
        app.run(args);
    }

    // ----------------------------------------------------------------------------------

    private Document dropboxPage;

    public void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Issue a command. (download|check-downloaded-files|convert-metadata|split-images)");
        } else {
            String cmd = args[0];

            if (cmd.equals(config.getCMD_DOWNLOAD())) {
                downloadFiles();
            } else if (cmd.equals(config.getCMD_CONVERT_XLS())) {
                DropboxFile xls = new DropboxFile();
                xls.name = "Ha2 files list.xls";

                XLStoCSV(xls);
            } else if (cmd.equals(config.getCMD_CHECK_DOWNLOADS())) {
                checkDownload();
            } else if (cmd.equals(config.getCMD_SPLIT_IMAGES())) {
                DropboxFile xls = new DropboxFile();
                xls.name = "Ha2 files list.xls";

                List<DropboxImageFile> images = processExcel(xls);
                imageMagick(images);
            }
        }
    }

    /**
     * Split images using ImageMagick and rename each image, loosely following our
     * archive naming convention.
     *
     * @param images list of DropboxImageFiles
     * @throws IOException
     * @throws InterruptedException
     */
    private void imageMagick(List<DropboxImageFile> images) throws IOException, InterruptedException {
        Path inPath = Paths.get(config.getDOWNLOAD_DIRECTORY());
        Path splitPath = Paths.get(config.getSPLIT_DIRECTORY());

        if (!Files.exists(splitPath)) {
            System.out.println("Creating new directory. [" + splitPath.toString() + "]");
            Runtime.getRuntime().exec("mkdir " + splitPath.toString());
        }

        ExecutorService executorService = Executors.newFixedThreadPool(8);
        for (DropboxImageFile image : images) {
            String name = image.name.endsWith(".tif") ? image.name : image.name + ".tif";
            Path imagePath = inPath.resolve(name);

            List<String> pageNumbers = Arrays.asList(image.pageNumbers);

            if (!Files.isRegularFile(imagePath) || pageNumbers.isEmpty()) {
                continue;
            }

            if (pageNumbers.contains("none") || pageNumbers.size() != 2) {

                String filename = "";
                for (String str : pageNumbers) {
                    if (!str.equalsIgnoreCase("none")) {
                        filename = str;
                        break;
                    }
                }
                Path destination = splitPath.resolve(processName(filename));
                String command = "cp " + imagePath + " " + destination;

                executorService.execute(() -> {
                    System.out.println("Copying and renaming original file. ["
                            + imagePath.toString() + "] -> [" + destination.toString() + "]");

                    try {
                        Runtime.getRuntime().exec(command);
                    } catch (IOException e) {
                        System.err.println("Failed to copy file. [" + imagePath.toString() + "] to [" + destination.toString() + "]");
                    }
                });

                continue;
            }

            Path rectoPath  = splitPath.resolve(processName(pageNumbers.get(0)));
            ImageSplitterRunnable splitterRecto = new ImageSplitterRunnable(GRAVITY_RECTO, imagePath, rectoPath);
            executorService.execute(splitterRecto);

            if (pageNumbers.size() != 2) {
                continue;
            }

            Path versoPath = splitPath.resolve(processName(pageNumbers.get(1)));

            ImageSplitterRunnable splitterVerso = new ImageSplitterRunnable(GRAVITY_VERSO, imagePath, versoPath);
            executorService.execute(splitterVerso);
        }

        executorService.shutdown();
    }

    public String processName(String name) {

        if (name.equals("front outside cover")) {
            name = "binding frontcover";
        } else if (name.equals("back outside cover")) {
            name = "binding backcover";
        } else if (name.equals("back inside cover")) {
            name = "endmatter pastedown";
        } else if (name.equals("inside front cover")) {
            name = "frontmatter pastedown";
        } else if (name.equals("spine")) {
            name = "binding spine";
        } else if (name.equals("head") || name.equals("fore-edge") || name.equals("tail")) {
            name = "misc " + name.replace("-", "");
        }

        String[] parts = name.split("\\s+");

        StringBuilder sb = new StringBuilder(config.getBOOK_ID());
        for (String part : parts) {
            if (part.equalsIgnoreCase("fol.")) {
                continue;
            } else if (part.equals("(blank)")) {
                continue;
            }

            if (part.matches("\\d+(r|v)")) {
                int n = Integer.parseInt(part.substring(0, part.length() - 1));
                part = String.format("%03d", n) + part.substring(part.length() - 1);
            } else if (part.equals("back")) {
                part = "endmatter";
            } else if (part.equals("front")) {
                part = "frontmatter";
            } else if (part.equals("endleaf")) {
                part = "flyleaf";
            }

            sb.append('.');
            sb.append(part);

        }

        sb.append(".tif");
        return sb.toString();
    }

    private boolean existsInPath(DropboxFile file, Path path) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
            for (Path p : ds) {
                String filename = p.getFileName().toString().split("\\.")[0];
                if (file.name.equals(filename)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks files in file system against the XLS metadata. If a file specified in the metadata
     * does not exist on the file system, an attempt will be made to download it.
     *
     * @throws IOException
     */
    private void checkDownload() throws IOException {
        System.out.println("Checking downloaded files.");

        DropboxFile xls = new DropboxFile();
        xls.name = "Ha2 files list.xls";

        List<DropboxImageFile> images = processExcel(xls);
        List<String> errors = new ArrayList<>();

        Path downloadPath = Paths.get(config.getDOWNLOAD_DIRECTORY());
        for (DropboxImageFile image : images) {
            if (!existsInPath(image, downloadPath)) {
                try {
                    System.out.println("Trying to re-download file. [" + image.name + "]");
                    downloadFile(image.name);
                } catch (Exception e) {
                    errors.add("Image specified in XML file [" + image.name + "] does not exist in the path ["
                            + downloadPath.toString() + "].");
                }
            }
        }

        if (errors.size() > 0) {
            for (String err : errors) {
                System.out.println("Error: " + err);
            }
        }
    }

    private void downloadFile(String filename) throws IOException {
        List<DropboxFile> files = getFilesList();

        System.out.println();
        ExecutorService service = Executors.newFixedThreadPool(1);
        for (DropboxFile file : files) {
            String dropboxFileName = file.name.split("\\.")[0];

            if (filename.equals(dropboxFileName)) {
                String outputFilePath = config.getDOWNLOAD_DIRECTORY() + file.name;

                DownloadRunnable download = new DownloadRunnable(file, outputFilePath);
                service.execute(download);
            }
        }

        service.shutdown();
    }

    private Document getDropboxPageFromWeb() throws IOException {
        if (dropboxPage != null) {
            return dropboxPage;
        }

        String baseUrl = config.getBASE_URL();

        System.out.println("Connecting to the Dropbox page. Please wait a few moments.");
        System.out.println("  [" + baseUrl + "]");
        dropboxPage = Jsoup.connect(baseUrl).timeout(config.getMAX_TIMEOUT()).get(); // 30s timeout
        System.out.println("Connected to Dropbox, extracting file URLs");

        return dropboxPage;
    }

    private List<DropboxFile> getFilesList() throws IOException {
        Document dbPage = getDropboxPageFromWeb();

        // Look in the file list view
        Elements listEls = dbPage.select(config.getSELECTOR());

        if (listEls == null || listEls.size() == 0) {
            System.err.println("Could not find file download links.");
            System.exit(1);
        }

        List<DropboxFile> dbFiles = new ArrayList<>();
        for (Element el : listEls) {
            String url = el.attr("href");
            if (isNotBlank(url)) {
                // Make sure the URL is for direct download
                if (url.endsWith("dl=0")) {
                    url = url.substring(0, url.length() - 1) + "1";
                }

                // Extract file name from URL
                String[] parts = url.split("/");
                String filename = parts[parts.length - 1];

                if (filename.endsWith("?dl=1")) {
                    filename = filename.substring(0, filename.length() - 5);
                }

                DropboxFile file = new DropboxFile();
                file.name = URLDecoder.decode(filename, "UTF-8");
                file.url = url;

                dbFiles.add(file);
            }
        }

        return dbFiles;
    }

    /**
     *
     * @throws Exception
     */
    private void downloadFiles() throws Exception {
        if (!Files.exists(Paths.get(config.getDOWNLOAD_DIRECTORY()))) {
            System.out.println("Creating new directory. [" + config.getDOWNLOAD_DIRECTORY() + "]");
            Runtime.getRuntime().exec("mkdir " + config.getDOWNLOAD_DIRECTORY());
        }

        List<DropboxFile> dbFiles = getFilesList();

        System.out.println();
        // Download multiple files simultaneously
        ExecutorService executorService = Executors.newFixedThreadPool(config.getMAX_THREADS());
        for (DropboxFile dbFile : dbFiles) {
            String outputFilePath = config.getDOWNLOAD_DIRECTORY() + dbFile.name;

            DownloadRunnable downloader = new DownloadRunnable(dbFile, outputFilePath);
            executorService.execute(downloader);
        }

        executorService.shutdown();
    }

    /**
     *
     * @param file metadata XLS file
     */
    private List<DropboxImageFile> processExcel(DropboxFile file) {
        List<DropboxImageFile> images = new ArrayList<>();

        if (!file.isXLS()) {
            System.out.println("File [" + file.name + "] does not exist.");
            return images;
        }

        Path inPath = Paths.get(config.getDOWNLOAD_DIRECTORY() + file.name);
        try (InputStream in = Files.newInputStream(inPath)) {

            Workbook wb = new HSSFWorkbook(in);
            Sheet sheet = wb.getSheetAt(0);

            boolean isFirst = true;
            for (Row row : sheet) {
                // First row = column headers. Skip.
                if (isFirst) {
                    isFirst = false;
                    continue;
                }

                if (row.getPhysicalNumberOfCells() != 6) {
                    continue;
                }

                DropboxImageFile image = new DropboxImageFile();

                image.name = row.getCell(0).getStringCellValue();
                image.callNumber = row.getCell(1).getStringCellValue();
                image.title = row.getCell(2).getStringCellValue();
                image.publicationDate = row.getCell(3).getStringCellValue();

                String pn = row.getCell(4).getStringCellValue();
                image.pageNumbers = pn.split(config.getPAGE_NUMBER_DELIMITER());

                String so = row.getCell(5).getStringCellValue();
                try {
                    image.sortOrder = Integer.parseInt(so);
                } catch (NumberFormatException e) {
                    image.sortOrder = Integer.MAX_VALUE;
                }

                images.add(image);
            }
        } catch (IOException e) {
            System.err.println("Error: Cannot read file: [" + inPath.toString() + "]");
        }

        return images;
    }

    /**
     * Convert a Microsoft Office Excel spreadsheet to a .csv file. This file will have the
     * same name as the original file and will be created in the same directory.
     *
     * @param file metadata XLS file
     */
    private void XLStoCSV(DropboxFile file) {
        if (!file.isXLS()) {
            System.out.println("File [" + file.name + "] does not exist.");
            return;
        }

        String csv = null;

        Path inPath = Paths.get(config.getDOWNLOAD_DIRECTORY() + file.name);
        try (InputStream in = Files.newInputStream(inPath)) {

            Workbook wb = new HSSFWorkbook(in);
            Sheet sheet = wb.getSheetAt(0);

            int max_cols = 0;
            // Discover maximum number of columns
            for (Row row : sheet) {
                if (row.getLastCellNum() > max_cols) {
                    max_cols = row.getLastCellNum();
                }
            }

            StringBuilder sb = new StringBuilder();
            for (Row row : sheet) {
                for (int i = 0; i < max_cols; i++) {
                    String value = row.getCell(i).getStringCellValue();
                    boolean hasComma = value.contains(",");

                    if (hasComma) {
                        sb.append('"');
                        sb.append(value);
                        sb.append('"');
                    } else {
                        sb.append(value);
                    }

                    if (i != max_cols - 1) {
                        sb.append(",");
                    } else {
                        sb.append("\n");
                    }
                }
            }

            csv = sb.toString();

        } catch (IOException e) {
            System.err.println("Error: Failed to read file. [" + inPath.toString() + "]");
        }


        String outName = file.name;
        if (outName.endsWith(".xls")) {
            outName = outName.substring(0, outName.length() - 4);
        }

        Path outPath = Paths.get(config.getDOWNLOAD_DIRECTORY() + outName + ".csv");
        if (isBlank(csv) || Files.exists(outPath)) {
            return;
        }
        try (OutputStream out = Files.newOutputStream(outPath)) {
            IOUtils.write(csv, out, "UTF-8");
        } catch (IOException e) {
            System.err.println("Error: Failed to write file. [" + outPath.toString() + "]");
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.equals("");
    }

    private boolean isNotBlank(String str) {
        return !isBlank(str);
    }

}
