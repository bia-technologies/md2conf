package io.github.md2conf.maven.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConvertMd2WikiMojoPluginIT extends AbstractMd2ConfMojoIT {

    @Test
    void simple_convert() {
        // arrange
        Map<String, String> properties = mandatoryProperties();
        properties.put("inputDirectory", ".");
        // act
        var res = invokeGoalAndVerify("convert-md2wiki", "default", properties);
        Path outputPath = res.toPath().resolve("target/md2conf");
        assertThat(outputPath).isDirectoryContaining(path -> path.getFileName().toString().equals("confluence-content-model.json"));
        assertThat(outputPath).isDirectoryContaining("glob:**/*.wiki");
    }

    @Test
    void skip() {
        // arrange
        Map<String, String> properties = mandatoryProperties();
        properties.put("inputDirectory", ".");
        properties.put("skip", "true");
        // act
        var res = invokeGoalAndVerify("convert-md2wiki", "default", properties);
        Path outputPath = res.toPath().resolve("target/md2conf");
        assertThat(outputPath).doesNotExist();
    }

}