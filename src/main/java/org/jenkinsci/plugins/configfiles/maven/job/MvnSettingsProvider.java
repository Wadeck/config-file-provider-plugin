package org.jenkinsci.plugins.configfiles.maven.job;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import hudson.util.ListBoxModel;
import jenkins.mvn.SettingsProvider;
import jenkins.mvn.SettingsProviderDescriptor;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.jenkinsci.plugins.configfiles.common.CleanTempFilesAction;
import org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig;
import org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig.MavenSettingsConfigProvider;
import org.jenkinsci.plugins.configfiles.maven.security.CredentialsHelper;
import org.jenkinsci.plugins.configfiles.maven.security.ServerCredentialMapping;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
/**
 * This provider delivers the settings.xml to the job during job/project execution. <br>
 * <b>Important: Do not rename this class!!</b> For backward compatibility, this class is also created via reflection from the maven-plugin.
 *
 * @author Dominik Bartholdi (imod)
 */
public class MvnSettingsProvider extends SettingsProvider {

    private final static Logger LOGGER = Logger.getLogger(MvnSettingsProvider.class.getName());

    private String settingsConfigId;

    /**
     * Default constructor used to load class via reflection by the maven-plugin for backward compatibility
     */
    @Deprecated
    public MvnSettingsProvider() {
    }

    @DataBoundConstructor
    public MvnSettingsProvider(String settingsConfigId) {
        this.settingsConfigId = settingsConfigId;
    }

    public String getSettingsConfigId() {
        return settingsConfigId;
    }

    public void setSettingsConfigId(String settingsConfigId) {
        this.settingsConfigId = settingsConfigId;
    }

    @Override
    @CheckForNull
    public FilePath supplySettings(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        if (StringUtils.isBlank(settingsConfigId)) {
            return null;
        }

        Config c = ConfigFiles.getByIdOrNull(run, settingsConfigId);

        PrintStream console = listener.getLogger();
        if (c == null) {
            listener.error("Maven settings.xml with id '" + settingsConfigId + "' not found");
            return null;
        }
        if (StringUtils.isBlank(c.content)) {
            console.format("Ignore empty maven settings.xml with id " + settingsConfigId);
            return null;
        }

        MavenSettingsConfig config;
        if (c instanceof MavenSettingsConfig) {
            config = (MavenSettingsConfig) c;
        } else {
            config = new MavenSettingsConfig(c.id, c.name, c.comment, c.content, MavenSettingsConfig.isReplaceAllDefault, null);
        }

        FilePath workspaceTmpDir = WorkspaceList.tempDir(workspace);
        workspaceTmpDir.mkdirs();

        String fileContent = config.content;

        final List<ServerCredentialMapping> serverCredentialMappings = config.getServerCredentialMappings();
        final Map<String, StandardUsernameCredentials> resolvedCredentials = CredentialsHelper.resolveCredentials(run, serverCredentialMappings);
        final Boolean isReplaceAll = config.getIsReplaceAll();

        if (!resolvedCredentials.isEmpty()) {
            // temporary credentials files (ssh pem files...)
            List<String> tmpCredentialsFiles = new ArrayList<>();
            console.println("Inject in Maven settings.xml credentials (replaceAll: " + config.isReplaceAll + ") for: " + Joiner.on(",").join(resolvedCredentials.keySet()));
            try {
                fileContent = CredentialsHelper.fillAuthentication(fileContent, isReplaceAll, resolvedCredentials, workspaceTmpDir, tmpCredentialsFiles);
            } catch (IOException e) {
                throw new IOException("Exception injecting credentials for maven settings file '" + config.id + "' during '" + run + "'", e);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Exception injecting credentials for maven settings file '" + config.id + "' during '" + run + "'", e);
            }
            for (String tmpCredentialsFile : tmpCredentialsFiles) {
                run.addAction(new CleanTempFilesAction(tmpCredentialsFile));
            }
        }

        final FilePath mavenSettingsFile = workspaceTmpDir.createTempFile ("maven-","-settings.xml");
        mavenSettingsFile.copyFrom(org.apache.commons.io.IOUtils.toInputStream(fileContent, Charsets.UTF_8));

        LOGGER.log(Level.FINE, "Create {0} from {1}", new Object[]{mavenSettingsFile, config.id});

        // Temporarily attach info about the files to be deleted to the build - this action gets removed from the build again by
        // 'org.jenkinsci.plugins.configfiles.common.CleanTempFilesRunListener'
        run.addAction(new CleanTempFilesAction(mavenSettingsFile.getRemote()));

        if (run instanceof AbstractBuild) {
            AbstractBuild build = (AbstractBuild) run;
            build.getEnvironments().add(new SimpleEnvironment("MVN_SETTINGS", mavenSettingsFile.getRemote()));
        }

        return mavenSettingsFile;
    }

    @Extension(ordinal = 10)
    public static class DescriptorImpl extends SettingsProviderDescriptor {

        @Override
        public String getDisplayName() {
            return "provided settings.xml";
        }

        public ListBoxModel doFillSettingsConfigIdItems(@AncestorInPath ItemGroup context) {
            ListBoxModel items = new ListBoxModel();
            items.add("please select", "");
            for (Config config : ConfigFiles.getConfigsInContext(context, MavenSettingsConfigProvider.class)) {
                items.add(config.name, config.id);
            }
            return items;
        }
    }

}