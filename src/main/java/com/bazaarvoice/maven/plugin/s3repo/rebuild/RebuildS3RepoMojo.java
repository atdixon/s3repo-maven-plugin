package com.bazaarvoice.maven.plugin.s3repo.rebuild;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.bazaarvoice.maven.plugin.s3repo.S3RepositoryPath;
import com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraFileUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraIOUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.S3Utils;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.InputStreamFacade;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mojo (name = "rebuild-repo", requiresProject = false)
public final class RebuildS3RepoMojo extends AbstractMojo {

    /** Staging directory. This is where we will recreate the relevant *bucket* files (i.e., this acts as the
       root of the ). */
    @Parameter(property = "s3repo.stagingDirectory")
    private File stagingDirectory;

    /**
     * The s3 path to the root of the target repository.
     * These are all valid values:
     *      "s3://Bucket1/Repo1"
     *      "/Bucket/Repo1"
     */
    @Parameter (property = "s3repo.repositoryPath", required = true)
    private String s3RepositoryPath;

    @Parameter(property = "s3repo.accessKey")
    private String s3AccessKey;

    @Parameter(property = "s3repo.secretKey")
    private String s3SecretKey;

    /** Do not try to validate the current repository metadata before recreating the repository. */
    @Parameter(property = "s3repo.doNotValidate", defaultValue = "false")
    private boolean doNotValidate;

    @Parameter(property = "s3repo.removeOldSnapshots", defaultValue = "false")
    private boolean removeOldSnapshots;

    /** Execute all steps up to and excluding the upload to the S3. This can be set to true to perform a "dryRun" execution. */
    @Parameter(property = "s3repo.doNotUpload", defaultValue = "false")
    private boolean doNotUpload;

    /** Only upload the new repo metadata. */
    @Parameter(property = "s3repo.uploadMetadataOnly", defaultValue = "true")
    private boolean uploadMetadataOnly;

    /** Indicates whether we should clean the staging directory before pulling the repository; this is helpful because
      existing files in staging are not re-downloaded; this is especially helpful for debugging this plugin during
      development. */
    @Parameter(property = "s3repo.doNotPreClean", defaultValue = "false")
    private boolean doNotPreClean;

    /** The createrepo executable. */
    @Parameter(property = "s3repo.createrepo", defaultValue = "createrepo")
    private String createrepo;

    /** Comma-delimited **repo-relative** paths to exclude when rebuilding. Example value:
     *      path/to/awesome-artifact-1.4-SNAPSHOT1.noarch.rpm,path/to/awesome-artifact-1.4.noarch.rpm
     */
    @Parameter(property = "s3repo.excludes", defaultValue = "")
    private String excludes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        determineAndSetStagingDirectoryIfNeeded();

        RebuildContext context = new RebuildContext();

        context.setS3Session(createS3Client());
        context.setS3RepositoryPath(parseS3RepositoryPath());
        context.setLocalYumRepo(determineLocalYumRepo(context.getS3RepositoryPath()));
        context.setExcludedFiles(parseExcludedFiles());

        // always clean staging directory
        maybeCleanStagingDirectory();

        // download entire repository
        downloadEntireRepository(context);
        // perform some checks to ensure repository is as expected if doNotValidate = false
        maybeValidateRepository(context);
        // remove old snapshots if removeOldSnapshots = true
        maybeRemoveOldSnapshots(context);
        // rebuild -- rerun createrepo
        rebuildRepo(context);
        // upload repository and delete old snapshots etc. if doNotUpload = false
        maybeUploadRepository(context);
    }

    private List<String> parseExcludedFiles() {
        List<String> excludedFiles = Lists.newArrayList();
        if (!StringUtils.isEmpty(excludes)) {
            for (String excludedFile : excludes.split(",")) {
                if (!StringUtils.isEmpty(excludedFile)) {
                    excludedFiles.add(excludedFile);
                }
            }
        }
        return excludedFiles;
    }

    private void determineAndSetStagingDirectoryIfNeeded() {
        if (stagingDirectory == null) {
            stagingDirectory = Files.createTempDir();
        }
        getLog().info("I will use " + stagingDirectory.getAbsolutePath() + " as your staging directory.");
    }

    private void maybeCleanStagingDirectory() throws MojoExecutionException {
        if (doNotPreClean) {
            getLog().warn("Not cleaning staging directory!!!");
            return;
        }
        ExtraFileUtils.createOrCleanDirectory(stagingDirectory);
    }

    private void maybeUploadRepository(RebuildContext context) throws MojoExecutionException {
        String logPrefix = "";
        if (doNotUpload) {
            getLog().info("NOTE: Per configuration, we will not perform any remote operations on the S3 repository.");
            logPrefix = "SKIPPING: ";
        }
        final S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        final String targetBucket = s3RepositoryPath.getBucketName();
        AmazonS3 s3Session = context.getS3Session();
        File directoryToUpload = uploadMetadataOnly
                ? context.getLocalYumRepo().repoDataDirectory() // only the repodata directory
                : stagingDirectory; // the entire staging directory/bucket
        for (File toUpload : ExtraIOUtils.listAllFiles(directoryToUpload)) {
            // relativize path wrt to *stagingDirectory* which represents our *bucket*
            final String bucketKey = localFileToS3BucketKey(toUpload);
            getLog().info(logPrefix + "Uploading " + toUpload.getName() + " to s3://" + targetBucket + "/" + bucketKey + "...");
            if (!doNotUpload) {
                s3Session.putObject(new PutObjectRequest(targetBucket, bucketKey, toUpload));
            }
        }
        // delete any excluded files remotely.
        for (String repoRelativePath : context.getExcludedFiles()) {
            final String bucketKey = s3RepositoryPath.hasBucketRelativeFolder()
                ? s3RepositoryPath.getBucketRelativeFolder() + "/" + repoRelativePath
                : repoRelativePath;
            getLog().info(logPrefix + "Deleting excluded file '" + bucketKey + "' from S3 (if it exists)...");
            if (!doNotUpload) {
                context.getS3Session().deleteObject(targetBucket, bucketKey);
            }
        }
        // and finally, delete any remote bucket keys we wish to remove (e.g., old snaphots)
        for (SnapshotDescription toDelete : context.getSnapshotsToDeleteRemotely()) {
            getLog().info(logPrefix + "Deleting old snapshot '" + toDelete + "' from S3...");
            if (!doNotUpload) {
                context.getS3Session().deleteObject(targetBucket, toDelete.getBucketKey());
            }
        }
        for (RemoteSnapshotRename toRename : context.getSnapshotsToRenameRemotely()) {
            final String sourceBucketKey = toRename.getSource().getBucketKey();
            final String targetBucketKey = toRename.getNewBucketKey();
            getLog().info("Renaming key '" + sourceBucketKey + "' to '" + toRename.getNewBucketKey() + "' in S3...");
            if (!doNotUpload) {
                s3Session.copyObject(targetBucket, sourceBucketKey, targetBucket, targetBucketKey);
                s3Session.deleteObject(targetBucket, sourceBucketKey);
            }
        }
    }

    private String localFileToS3BucketKey(File toUpload) throws MojoExecutionException {
        String relativizedPath = ExtraIOUtils.relativize(stagingDirectory, toUpload);
        // replace *other* file separators with S3-style file separators and strip first & last separator
        relativizedPath = relativizedPath.replaceAll("\\\\", "/").replaceAll("^/", "").replaceAll("/$", "");
        return relativizedPath;
    }

    private void rebuildRepo(RebuildContext context) throws MojoExecutionException {
        getLog().info("Rebuilding repo...");
        context.getLocalYumRepo().createRepo();
    }

    private void maybeRemoveOldSnapshots(RebuildContext context) throws MojoExecutionException {
        if (removeOldSnapshots) {
            getLog().info("Removing old snapshots...");
            Map<String, List<SnapshotDescription>> snapshots = context.getBucketKeyPrefixToSnapshots();
            for (String snapshotKeyPrefix : snapshots.keySet()) {
                List<SnapshotDescription> snapshotsRepresentingSameInstallable = snapshots.get(snapshotKeyPrefix);
                if (snapshotsRepresentingSameInstallable.size() > 1) {
                    // if there's more than one snapshot for a given installable, then we have cleanup to do
                    Collections.sort(snapshotsRepresentingSameInstallable, new Comparator<SnapshotDescription>() {
                        @Override
                        public int compare(SnapshotDescription left, SnapshotDescription right) {
                            // IMPORTANT: this ensures that *latest/newer* artifacts are ordered first
                            return right.getOrdinal() - left.getOrdinal();
                        }
                    });
                    // start with *second* artifact; delete it and everything after it (these are the older artifacts)
                    for (int i = 1; i < snapshotsRepresentingSameInstallable.size(); ++i) {
                        SnapshotDescription toDelete = snapshotsRepresentingSameInstallable.get(i);
                        getLog().info("Deleting old snapshot '" + toDelete.getBucketKey() + "', locally...");
                        // delete object locally so createrepo step doesn't pick it up
                        deleteBucketRelativePath(toDelete.getBucketKey());
                        // we'll also delete the object from s3 but only after we upload the repository metadata
                        // (so we don't confuse any repo clients who are reading the current repo metadata)
                        context.addSnapshotToDelete(toDelete);
                    }
                    // rename the lastest snapshot (which is the first in our list) discarding it's SNAPSHOT numeric suffix
                    renameSnapshotLocalFileByStrippingSnapshotNumerics(context, snapshotsRepresentingSameInstallable.get(0));
                }
            }
        }
    }

    private void renameSnapshotLocalFileByStrippingSnapshotNumerics(RebuildContext context, SnapshotDescription snapshotDescription) throws MojoExecutionException {
        final File latestSnapshotFile = new File(stagingDirectory, /*bucket-relative path*/snapshotDescription.getBucketKey());
        final File renameTo = new File(latestSnapshotFile.getParent(), tryStripSnapshotNumerics(latestSnapshotFile.getName()));
        getLog().info("Renaming " + ExtraIOUtils.relativize(stagingDirectory, latestSnapshotFile)
                + " => " + renameTo.getName() /*note can't relativize non-existent file*/);
        if (latestSnapshotFile.renameTo(renameTo)) {
            // rename was successful -- also ensure that we queue up the snapshot to rename it remotely
            context.addSnapshotToRename(RemoteSnapshotRename.withNewBucketKey(snapshotDescription, localFileToS3BucketKey(renameTo)));
        } else {
            getLog().warn("Failed to rename " + latestSnapshotFile.getPath() + " to " + renameTo.getPath());
        }
    }

    private void deleteBucketRelativePath(String bucketRelativePath) throws MojoExecutionException {
        final File toDelete = new File(stagingDirectory, bucketRelativePath);
        if (!toDelete.isFile()) {
            throw new MojoExecutionException("Cannot delete non-existent file: " + toDelete);
        }
        if (!toDelete.delete()) {
            throw new MojoExecutionException("Failed to delete file: " + toDelete);
        }
    }

    private void maybeValidateRepository(RebuildContext context) throws MojoExecutionException {
        if (doNotValidate) {
            return;
        }
        getLog().info("Validating downloaded repository...");
        LocalYumRepoFacade localYumRepo = context.getLocalYumRepo();
        if (!localYumRepo.isRepoDataExists()) {
            throw new MojoExecutionException("Repository does not exist!");
        }
        // list of files (repo-relative paths)
        List<String> fileList = localYumRepo.parseFileListFromRepoMetadata();
        for (String repoRelativePath : fileList) {
            if (!localYumRepo.hasFile(repoRelativePath)) {
                throw new MojoExecutionException("Repository metadata declared file " + repoRelativePath + " but the file does not exist.");
            }
        }
    }

    /** Create a {@link com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade} which will allow us to query and operate on a local (on-disk) yum repository. */
    private LocalYumRepoFacade determineLocalYumRepo(S3RepositoryPath s3RepositoryPath) {
        return new LocalYumRepoFacade(
                s3RepositoryPath.hasBucketRelativeFolder()
                        ? new File(stagingDirectory, s3RepositoryPath.getBucketRelativeFolder())
                        : stagingDirectory, createrepo, getLog());
    }

    private AmazonS3Client createS3Client() {
        if (s3AccessKey != null || s3SecretKey != null) {
            return new AmazonS3Client(new BasicAWSCredentials(s3AccessKey, s3SecretKey));
        } else {
            return new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
        }
    }

    /** Download the entire repository. (Also adds SNAPSHOT metadata to the provided <code>context</code>.) */
    private void downloadEntireRepository(RebuildContext context) throws MojoExecutionException {
        getLog().info("Downloading entire repository...");
        getLog().debug("Excluded files = " + context.getExcludedFiles());
        S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
                .withBucketName(s3RepositoryPath.getBucketName());
        String prefix = ""; // capture prefix for debug logging
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            prefix = s3RepositoryPath.getBucketRelativeFolder() + "/";
            listObjectsRequest.withPrefix(prefix);
        }
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        getLog().debug("Found " + result.size() + " objects in bucket '" + s3RepositoryPath.getBucketName()
                + "' with prefix '" + s3RepositoryPath.getBucketRelativeFolder() + "/" + "'...");
        for (S3ObjectSummary summary : result) {
            if (summary.getKey().endsWith("/")) {
                getLog().info("No need to download " + summary.getKey() + ", it's a folder");
                continue;
            }
            String asRepoRelativePath = toRepoRelativePath(summary, s3RepositoryPath);
            getLog().debug("repo relative path = " + asRepoRelativePath);
            if (context.getExcludedFiles().contains(asRepoRelativePath)) {
                getLog().info("No need to download " + summary.getKey() + ", it's explicitly excluded (and will be removed from S3)");
                continue;
            }
            // for every item in the repository, add it to our snapshot metadata if it's a snapshot artifact
            maybeAddSnapshotMetadata(summary, context);
            if (new File(stagingDirectory, summary.getKey()).isFile()) {
                // file exists (likely due to doNotPreClean = true); do not download
                getLog().info("Skipping download of '" + summary.getKey() + "' from S3 as file already exists...");
            } else { // file doesn't yet exist
                getLog().info("Downloading '" + summary.getKey() + "' from S3...");
                final S3Object object = context.getS3Session()
                        .getObject(new GetObjectRequest(s3RepositoryPath.getBucketName(), summary.getKey()));
                try {
                    File targetFile =
                            new File(stagingDirectory, /*assume object key is bucket-relative path to filename with extension*/summary.getKey());
                    Files.createParentDirs(targetFile);
                    FileUtils.copyStreamToFile(new InputStreamFacade() {
                        @Override
                        public InputStream getInputStream() throws IOException {
                            return object.getObjectContent();
                        }
                    }, targetFile);
                } catch (IOException e) {
                    throw new MojoExecutionException("failed to download object from s3: " + summary.getKey(), e);
                }
            }
        }
    }

    private void maybeAddSnapshotMetadata(S3ObjectSummary summary, RebuildContext context) {
        final int lastSlashIndex = summary.getKey().lastIndexOf("/");
        // determine the path to the file (excluding the filename iteself); this path may be empty, otherwise it contains
        // a "/" suffix
        final String path = lastSlashIndex > 0 ? summary.getKey().substring(0, lastSlashIndex + 1) : "";
        // determine the file name (without any directory path elements)
        final String fileName = lastSlashIndex > 0 ? summary.getKey().substring(lastSlashIndex + 1) : summary.getKey();
        final int snapshotIndex = fileName.indexOf("SNAPSHOT");
        if (snapshotIndex > 0) { // heuristic: we have a SNAPSHOT artifact here
            final String prefixWithoutPath = fileName.substring(0, snapshotIndex);
            final String bucketKeyPrefix = path + prefixWithoutPath;
            // try to convert anything after the SNAPSHOT into an ordinal value
            final int ordinal = toOrdinal(fileName.substring(snapshotIndex));
            getLog().debug("Making note of snapshot '" + summary.getKey() + "'; using prefix = " + bucketKeyPrefix);
            // ASSERT: bucketKeyPrefix is *full path* of bucket key up to and excluding the SNAPSHOT string and anything after it.
            context.addSnapshotDescription(new SnapshotDescription(bucketKeyPrefix, summary.getKey(), ordinal));
        }
    }

    private static int toOrdinal(String snapshotSuffix) {
        // replace all non-digits
        String digitsOnly = snapshotSuffix.replaceAll("\\D", "");
        if (StringUtils.isEmpty(digitsOnly)) {
            return -1;
        }
        return Integer.parseInt(digitsOnly);
    }

    private S3RepositoryPath parseS3RepositoryPath() throws MojoExecutionException {
        try {
            S3RepositoryPath parsed = S3RepositoryPath.parse(s3RepositoryPath);
            if (parsed.hasBucketRelativeFolder()) {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' and folder '" + parsed.getBucketRelativeFolder() + "' as repository...");
            } else {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' as repository...");
            }
            return parsed;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse S3 repository path: " + s3RepositoryPath, e);
        }
    }

    private String tryStripSnapshotNumerics(String snapshotFileName) {
        if (StringUtils.countMatches(snapshotFileName, "SNAPSHOT") != 1) {
            getLog().warn("filename did not look like a normal SNAPSHOT");
            return snapshotFileName; // do nothing
        }
        return snapshotFileName.replaceAll("SNAPSHOT\\d+\\.", "SNAPSHOT.");
    }

    private static String toRepoRelativePath(S3ObjectSummary summary, S3RepositoryPath s3RepositoryPath) {
        return s3RepositoryPath.hasBucketRelativeFolder()
            ? summary.getKey().replaceFirst("^\\Q" + s3RepositoryPath.getBucketRelativeFolder() + "/\\E", "")
            : summary.getKey();
    }

}
