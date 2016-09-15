/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.marketplace;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Tool used to create, sign, and publish packages.
 */
public class Tool {
  private static final Logger LOG = LoggerFactory.getLogger(Tool.class);

  public static void main(String[] args) throws Exception {

    Options options = new Options()
      .addOption(new Option("h", "help", false, "Print this usage message."))
      .addOption(new Option("k", "key", true,
                            "File containing the private key used to sign package specs and archives."))
      .addOption(new Option("d", "dir", true,
                            "Directory containing packages. Defaults to the current working directory."))
      .addOption(new Option("b", "bucket", true, "The S3 bucket to publish packages to."))
      .addOption(new Option("c", "config", true,
                            "The location of the s3 config file, containing the access key and secret key. " +
                              "The config file must specify properties as <property> = <value>. It must contain " +
                              "at least the access_key and secret_key properties."));

    CommandLineParser parser = new BasicParser();
    CommandLine commandLine = parser.parse(options, args);
    String[] commandArgs = commandLine.getArgs();

    // if help is an option
    if (commandLine.hasOption("h") || commandArgs.length == 0) {
      HelpFormatter helpFormatter = new HelpFormatter();
      helpFormatter.printHelp(
        Packager.class.getName() + " command",
        "Supported commands are 'clean', 'build', and 'publish'.\n" +
          "'clean' will delete any existing archives and the packages.json catalog.\n" +
          "'build' will create package archives and the package.json catalog listing all packages found. " +
          "Expects packages to conform to a specific directory structure. " +
          "Each package should put its contents in the <base>/packages/<package-name>/<package-version> directory. " +
          "In that directory, there must be a spec.json file.\n" +
          "If the package contains a license, it must be named license.txt.\n" +
          "If the package contains an icon, it must be named icon.jpg.\n" +
          "Anything else in the package directory will be zipped up into a file named archive.zip.\n" +
          "'publish' will push the packages.json catalog, zips, and specs to s3.\n" +
          "'build' will always run a 'clean' first. 'publish' will always run a 'clean' and a 'build' first.",
        options, "");
      System.exit(0);
    }

    String command = commandArgs[0];
    if (!command.equalsIgnoreCase("build") &&
      !command.equalsIgnoreCase("clean") &&
      !command.equalsIgnoreCase("publish")) {
      LOG.error("Unrecognized command '{}'. Command must be 'clean', 'build', or 'publish'.");
      System.exit(1);
    }

    // read and validate options

    // get package directory
    String packageDirectoryStr = commandLine.hasOption("d") ?
      commandLine.getOptionValue("d") : System.getProperty("user.dir");

    File packageDirectory = new File(packageDirectoryStr);
    if (!packageDirectory.exists()) {
      LOG.error("Directory '{}' does not exist.", packageDirectory);
      System.exit(1);
    }
    if (!packageDirectory.isDirectory()) {
      LOG.error("Directory '{}' is not a directory.", packageDirectory);
      System.exit(1);
    }

    String bucket = null;
    File cfgFile = null;
    if (command.equalsIgnoreCase("publish")) {
      if (!commandLine.hasOption('b')) {
        LOG.error("Must specify a bucket when publishing.");
        System.exit(1);
      }
      bucket = commandLine.getOptionValue('b');
      if (!commandLine.hasOption('c')) {
        LOG.error("Must specify an s3 config when publishing.");
        System.exit(1);
      }
      cfgFile = new File(commandLine.getOptionValue('c'));
      if (!cfgFile.exists()) {
        LOG.error("S3 config file '{}' does not exist.", cfgFile);
        System.exit(1);
      }
      if (!cfgFile.isFile()) {
        LOG.error("S3 config file '{}' is not a file.", cfgFile);
        System.exit(1);
      }
    }

    Packager packager = new Packager(packageDirectory);

    packager.clean();
    if (command.equalsIgnoreCase("clean")) {
      System.exit(0);
    }

    List<Package> packages = packager.buildPackages();
    if (command.equalsIgnoreCase("build")) {
      System.exit(0);
    }

    Publisher publisher = new S3Publisher(cfgFile, bucket);
    for (Package pkg : packages) {
      LOG.info("Publishing package {}-{}", pkg.getName(), pkg.getVersion());
      publisher.publishPackage(pkg);
    }
    LOG.info("Publishing catalog");
    publisher.publishCatalog(packager.getCatalog());
  }
}