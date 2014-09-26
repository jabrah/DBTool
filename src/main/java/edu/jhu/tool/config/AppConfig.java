package edu.jhu.tool.config;

import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 *
 */
public class AppConfig {

    @Inject @Named("baseUrl")
    private String BASE_URL;

    @Inject @Named("output.directory")
    private String OUTPUT_DIR;

    @Inject @Named("command.download")
    private String CMD_DOWNLOAD;

    @Inject @Named("threads.max")
    private int MAX_THREADS;

    @Inject @Named("timeout.max")
    private int MAX_TIMEOUT;

    @Inject @Named("dropbox.selector.fileDownloadAnchor")
    private String SELECTOR;

    @Inject @Named("metadata.pagenumber.delimiter")
    private String PAGE_NUMBER_DELIMITER;

    public String getBASE_URL() {
        return BASE_URL;
    }

    public String getOUTPUT_DIR() {
        return OUTPUT_DIR;
    }

    public String getCMD_DOWNLOAD() {
        return CMD_DOWNLOAD;
    }

    public int getMAX_THREADS() {
        return MAX_THREADS;
    }

    public String getSELECTOR() {
        return SELECTOR;
    }

    public int getMAX_TIMEOUT() {
        return MAX_TIMEOUT;
    }

    public String getPAGE_NUMBER_DELIMITER() {
        return PAGE_NUMBER_DELIMITER;
    }
}
