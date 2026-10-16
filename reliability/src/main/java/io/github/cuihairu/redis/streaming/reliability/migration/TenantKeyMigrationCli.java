package io.github.cuihairu.redis.streaming.reliability.migration;

import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Direction;
import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Family;
import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyMigrator.KeyMove;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Command line entry point for adopting or leaving a tenant in place, on top of
 * {@link TenantKeyMigrator} and {@link TenantKeyFamilies}:
 *
 * <pre>
 *   java ... TenantKeyMigrationCli --tenant acme                     # dry run, all families
 *   java ... TenantKeyMigrationCli --tenant acme --apply             # do it
 *   java ... TenantKeyMigrationCli --tenant acme --dir out --apply   # rollback
 *   java ... TenantKeyMigrationCli --tenant acme --family mq-data    # a subset
 *   java ... TenantKeyMigrationCli --tenant acme --prefix mq-data=myapp:stream
 * </pre>
 *
 * <p>Dry run is the default and a non-zero exit code means "not clean": {@link #EXIT_OK}
 * only when the run left the keyspace consistent with the intent. Operational
 * precondition is unchanged: stop the jobs and the writers of a family before migrating
 * it, then switch the tenant config after the keys have moved.</p>
 */
public final class TenantKeyMigrationCli {

    static final String DEFAULT_REDIS_URL = "redis://127.0.0.1:6379";
    static final int EXIT_OK = 0;
    static final int EXIT_MIGRATION_NOT_CLEAN = 1;
    static final int EXIT_USAGE = 2;

    private TenantKeyMigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Default entry point for a shell: reads STDOUT/STDERR and builds its own client. */
    public static int run(String[] args) {
        return run(args, TenantKeyMigrationCli::connect, System.out, System.err);
    }

    /** Runs one migration; the client factory and the streams are seams for tests. */
    public static int run(String[] args, Function<String, RedissonClient> clientFactory,
                          java.io.PrintStream out, java.io.PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(usage());
            return EXIT_USAGE;
        }
        if (options.help()) {
            out.println(usage());
            return EXIT_OK;
        }

        TenantKeyFamilies families = newTenantKeyFamilies(options);
        List<Family> selected = options.families().isEmpty()
                ? List.of(Family.values())
                : options.families();
        List<String> swallowed = families
                .swallowed(selected)
                .stream()
                .map(Family::cliName)
                .collect(Collectors.toList());
        if (!swallowed.isEmpty() && !options.allowOverlap()) {
            err.println("error: the selected families " + labels(selected)
                    + " also match the keys of the non-selected families " + swallowed
                    + "; migrate them in the same run or pass --allow-overlap");
            return EXIT_USAGE;
        }

        List<KeyMove> moves = options.direction() == Direction.ADOPT
                ? families.adoption(selected, options.tenant())
                : families.exit(selected, options.tenant());
        MigrationOptions migrationOptions = MigrationOptions.builder()
                .dryRun(!options.apply())
                .scanCount(options.scanCount())
                .build();

        RedissonClient client = clientFactory.apply(options.redisUrl());
        try {
            MigrationReport report = new TenantKeyMigrator(client).migrate(moves, migrationOptions);
            out.println(format(report, options));
            return report.isSuccess() ? EXIT_OK : EXIT_MIGRATION_NOT_CLEAN;
        } catch (Exception e) {
            err.println("error: " + e.getMessage());
            return EXIT_MIGRATION_NOT_CLEAN;
        } finally {
            client.shutdown();
        }
    }

    /** Parsed command line, immutable; {@link #families()} empty means every family. */
    public record Options(boolean help, boolean apply, boolean allowOverlap, String redisUrl,
                          String tenant, Direction direction, List<Family> families,
                          Map<Family, String> prefixes, int scanCount) {
    }

    /** Parses the command line; throws {@link IllegalArgumentException} on a bad line. */
    public static Options parse(String[] args) {
        boolean help = false, apply = false, allowOverlap = false;
        String redisUrl = DEFAULT_REDIS_URL;
        String tenant = null;
        Direction direction = Direction.ADOPT;
        List<Family> families = new ArrayList<>();
        Map<Family, String> prefixes = new EnumMap<>(Family.class);
        int scanCount = MigrationOptions.defaults().getScanCount();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--apply" -> apply = true;
                case "--allow-overlap" -> allowOverlap = true;
                case "--redis" -> redisUrl = requireValue(arg, value(args, ++i));
                case "--tenant" -> tenant = requireValue(arg, value(args, ++i));
                case "--dir" -> direction = Direction.of(value(args, ++i));
                case "--family" -> families.add(Family.of(value(args, ++i)));
                case "--prefix" -> parsePrefix(value(args, ++i), prefixes);
                case "--scan-count" -> scanCount = Integer.parseInt(value(args, ++i));
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }

        if (!help && tenant == null) {
            throw new IllegalArgumentException("--tenant is required (or pass --help)");
        }
        if (tenant != null) {
            tenant = TenantKeyFamilies.requireTenant(tenant);
        }
        if (scanCount < 1) {
            throw new IllegalArgumentException("--scan-count must be >= 1, got: " + scanCount);
        }
        return new Options(help, apply, allowOverlap, redisUrl, tenant, direction,
                List.copyOf(families), Map.copyOf(prefixes), scanCount);
    }

    private static TenantKeyFamilies newTenantKeyFamilies(Options options) {
        TenantKeyFamilies families = TenantKeyFamilies.defaults();
        for (Map.Entry<Family, String> prefix : options.prefixes().entrySet()) {
            families = families.with(prefix.getKey(), prefix.getValue());
        }
        return families;
    }

    private static void parsePrefix(String raw, Map<Family, String> prefixes) {
        int separator = raw.indexOf('=');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("--prefix expects family=prefix, got: " + raw);
        }
        prefixes.put(Family.of(raw.substring(0, separator)), raw.substring(separator + 1).trim());
    }

    private static String requireValue(String option, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(option + " requires a non-blank value");
        }
        return value;
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("expects a value");
        }
        return args[index];
    }

    private static String format(MigrationReport report, Options options) {
        String header = (report.isDryRun() ? "[dry-run] " : "[applied] ")
                + "tenant=" + options.tenant()
                + " direction=" + options.direction().label()
                + " redis=" + options.redisUrl()
                + " families=" + report.getFamilies().size()
                + " scanned=" + report.getTotalScanned()
                + " " + (report.isDryRun() ? "wouldMove=" + report.getTotalWouldMove()
                        : "moved=" + report.getTotalMoved())
                + " skipped=" + report.getTotalSkipped()
                + " failed=" + report.getTotalFailed()
                + " durationMs=" + report.getDurationMs()
                + " success=" + report.isSuccess();

        StringBuilder lines = new StringBuilder(header);
        for (MigrationReport.FamilyResult family : report.getFamilies()) {
            lines.append("\n  ")
                    .append(family.getFromPrefix())
                    .append(" -> ")
                    .append(family.getToPrefix())
                    .append("  scanned=")
                    .append(family.getScanned());
            if (report.isDryRun()) {
                lines.append(" wouldMove=").append(family.getWouldMove());
            } else {
                lines.append(" moved=").append(family.getMoved());
            }
            lines.append(" skipped=").append(family.getSkipped())
                    .append(" failed=").append(family.getFailed());
            if (family.getError() != null) {
                lines.append(" error=").append(family.getError());
            }
        }
        if (!report.getErrors().isEmpty()) {
            lines.append("\n  run errors: ").append(String.join("; ", report.getErrors()));
        }
        return lines.toString();
    }

    private static String labels(List<Family> selected) {
        return "'" + selected.stream().map(Family::cliName).collect(Collectors.joining(", ")) + "'";
    }

    private static RedissonClient connect(String redisUrl) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        return Redisson.create(config);
    }

    public static String usage() {
        return "usage: TenantKeyMigrationCli --tenant <name> [--apply] [--dir in|out] "
                + "[--family <family>] [--prefix <family>=<prefix>] [--redis <url>] "
                + "[--scan-count <n>] [--allow-overlap] [--help]\n"
                + "  --tenant           tenant to adopt or leave (no colon, 1..64 of "
                + "[A-Za-z0-9._-]); 'default' migrates nothing\n"
                + "  --apply            actually move the keys (default: dry run)\n"
                + "  --dir in|out       in = adopt the tenant segment, out = remove it\n"
                + "  --family           one of " + Family.labels() + " (default: all)\n"
                + "  --prefix           source prefix of one family for a customized "
                + "deployment, repeatable\n"
                + "  --redis            redis url (default: " + DEFAULT_REDIS_URL + ")\n"
                + "  --scan-count       SCAN COUNT hint (default 100)\n"
                + "  --allow-overlap    proceed although a selected family would swallow a "
                + "non-selected one\n"
                + "exit codes: 0 clean, 1 not clean (collision or failure), 2 usage error";
    }
}
