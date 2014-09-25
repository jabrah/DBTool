package edu.jhu.tool;

import com.google.inject.Guice;
import com.google.inject.Injector;
import edu.jhu.tool.config.AppConfig;
import edu.jhu.tool.config.AppModule;
import org.apache.commons.io.FileUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
public class App  {

    private class DropboxFile {
        String name;
        String url;

        boolean isImage() {
            return name.endsWith(".tif");
        }
        boolean isXLS() {
            return name.endsWith(".xls");
        }
    }

    private class DropboxImageFile extends DropboxFile {
        String callNumber;
        String title;
        String publicationDate;
        String[] pageNumbers;
        int sortOrder;
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

            System.out.println("Downloading [" + filename + "] from [" + url + "]");

            int timeouts = 30000;
            try {
                FileUtils.copyURLToFile(new URL(url), outPath.toFile(), timeouts, timeouts);
            } catch (Exception e) {
                e.printStackTrace();
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

    public void run(String[] args) throws Exception {
//        if (args.length == 0) {
//            downloadFiles();
//        } else {
//            String cmd = args[0];
//
//            if (cmd.equals(config.getCMD_DOWNLOAD())) {
//                downloadFiles();
//            }
//        }

        DropboxFile xls = new DropboxFile();
        xls.name = "Ha2 files list.xls";

        processExcel(xls);

    }

    private void downloadFiles() throws Exception {
        String baseUrl = config.getBASE_URL();

        System.out.println("Connecting to the Dropbox page. Please wait a few moments.");
        System.out.println("  [" + baseUrl + "]");
        Document dbPage = Jsoup.connect(baseUrl).timeout(config.getMAX_TIMEOUT()).get(); // 30s timeout
        System.out.println("Connected to Dropbox, extracting file URLs");

        // Look in the file list view
        Elements listEls = dbPage.select(config.getSELECTOR());

        if (listEls == null || listEls.size() == 0) {
            System.err.println("Could not find file download links.");
            System.exit(1);
        }

        // Map (file name) -> (download URL)
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

        System.out.println();
        // Download multiple files simultaneously
        ExecutorService executorService = Executors.newFixedThreadPool(config.getMAX_THREADS());
        for (DropboxFile dbFile : dbFiles) {
            String outputFilePath = config.getOUTPUT_DIR() + dbFile.name;

            DownloadRunnable downloader = new DownloadRunnable(dbFile, outputFilePath);
            executorService.execute(downloader);
        }
    }



    private void processExcel(DropboxFile file) {
        if (!file.isXLS()) {
            System.out.println("File [" + file.name + "] does not exist.");
            return;
        }

        Path inPath = Paths.get(config.getOUTPUT_DIR() + file.name);
        try (InputStream in = Files.newInputStream(inPath)) {

            Workbook wb = new HSSFWorkbook(in);
            Sheet sheet = wb.getSheetAt(0);

            boolean isFirst = true;
            for (Row row : sheet) {
                if (isFirst) {
                    isFirst = false;
                    continue;
                }

                if (row.getPhysicalNumberOfCells() != 6) {
                    // filename
                    // call number
                    // title
                    // publication date
                    // page numbers
                    // sort order
                }

                Cell cell = row.getCell(0);

                System.out.println(cell.getStringCellValue());
            }

        } catch (IOException e) {
            System.err.println("Error: Cannot read file: [" + inPath.toString() + "]");
        }
//        InputStream inp = new FileInputStream("workbook.xls");
//        //InputStream inp = new FileInputStream("workbook.xlsx");
//
//        Workbook wb = WorkbookFactory.create(inp);
//        Sheet sheet = wb.getSheetAt(0);
//        Row row = sheet.getRow(2);
//        Cell cell = row.getCell(3);
    }

    private boolean isBlank(String str) {
        return str == null || str.equals("");
    }

    private boolean isNotBlank(String str) {
        return !isBlank(str);
    }

}