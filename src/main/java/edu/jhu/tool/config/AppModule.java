package edu.jhu.tool.config;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 *
 */
public class AppModule extends AbstractModule {

    @Override
    protected void configure() {

        Names.bindProperties(binder(), getProperties());

    }

    private Properties getProperties() {
        Properties props = new Properties();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("app.properties")) {
            props.load(in);
        } catch (IOException e) {
            // TODO log
        }

        return props;
    }

}
