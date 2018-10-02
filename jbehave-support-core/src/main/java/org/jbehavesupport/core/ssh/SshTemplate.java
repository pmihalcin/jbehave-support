package org.jbehavesupport.core.ssh;

import static java.lang.Long.valueOf;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.nio.charset.Charset.defaultCharset;
import static java.time.ZoneId.of;
import static java.time.ZonedDateTime.now;
import static java.time.format.DateTimeFormatter.ofPattern;
import static net.schmizz.sshj.common.IOUtils.readFully;
import static org.springframework.util.Assert.isTrue;
import static org.springframework.util.Assert.notNull;
import static org.springframework.util.StreamUtils.copyToString;
import static org.springframework.util.StringUtils.isEmpty;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.springframework.core.io.ClassPathResource;

public class SshTemplate {
    private final SshSetting sshSetting;
    private final String timestampFormat;
    private final String timestampPosixRegex;
    private final String getLogBetweenTimestampsCommand;
    private final RollingLogResolver rollingLogResolver;
    private SSHClient sshClient;

    private interface Commands {
        String TIMEZONE = "timedatectl | grep zone | grep -Po '(.*zone: )\\K(\\S+)'";
        String EPOCH_TIME = "date +%s";
    }

    private interface Constants {
        String CMD_DELIMITER = "; ";
    }

    public SshTemplate(SshSetting sshSetting, String timestampFormat, String timestampPosixRegex, RollingLogResolver rollingLogResolver) throws IOException {
        isTrue(!isEmpty(sshSetting.getLogPath()), "log path must not be null or empty");
        isTrue(!isEmpty(timestampFormat), "timestamp format must not be null or empty");
        isTrue(!isEmpty(timestampPosixRegex), "timestamp posix regex must not be null or empty");

        this.sshSetting = sshSetting;
        this.timestampFormat = timestampFormat;
        this.timestampPosixRegex = timestampPosixRegex;
        this.rollingLogResolver = rollingLogResolver;
        this.getLogBetweenTimestampsCommand = copyToString(new ClassPathResource("get-log-between-timestamps-template.awk").getInputStream(), defaultCharset());
    }

    public SshSetting getSshSetting() {
        return sshSetting;
    }

    public SshLog copyLog(ZonedDateTime startTime, ZonedDateTime endTime) throws IOException {
        notNull(startTime, "startTime cannot be null");
        notNull(endTime, "endTime cannot be null");

        // if server has different time (ntp out of sync etc.)
        Long serverEpochTime = valueOf(executeCommand(Commands.EPOCH_TIME).trim());
        Long timeOffset = serverEpochTime - now().toEpochSecond();

        // if server has different time zone
        String timeZone = executeCommand(Commands.TIMEZONE).trim();
        ZonedDateTime serverStartTime = startTime.withZoneSameInstant(of(timeZone)).plusSeconds(timeOffset);
        ZonedDateTime serverEndTime = endTime.withZoneSameInstant(of(timeZone)).plusSeconds(timeOffset);

        List<String> logPaths = rollingLogResolver.resolveLogNames(sshSetting.getLogPath(), this,
            serverStartTime, serverEndTime);
        StringBuilder logs = new StringBuilder();
        for (String logPath : logPaths) {
            logs.append(copySingleLogFile(serverStartTime, serverEndTime, timestampPosixRegex, logPath));
        }
        return new SshLog(logs.toString(), sshSetting);
    }

    private String copySingleLogFile(final ZonedDateTime startTime, final ZonedDateTime endTime,
                                     final String timestampPosixRegex, final String logPath) throws IOException {
        DateTimeFormatter formatter = ofPattern(timestampFormat);
        String getLogBetweenTimestamps = format(getLogBetweenTimestampsCommand,
            startTime.format(formatter), endTime.format(formatter), timestampPosixRegex, logPath);
        return executeCommand(getLogBetweenTimestamps).trim();
    }

    public String executeCommands(String... commands) throws IOException {
        String finalCommand = join(Constants.CMD_DELIMITER, commands);
        return executeCommand(finalCommand);
    }

    private String executeCommand(String cmd) throws IOException {
        isTrue(!isEmpty(cmd), "cmd must not be null or empty");
        try (
            Session session = getSshClient().startSession();
            Session.Command command = session.exec(cmd)
        ) {
            String result = readFully(command.getInputStream()).toString();
            command.join();
            return result;
        }
    }

    private SSHClient getSshClient() throws IOException {
        if (sshClient == null || !sshClient.isConnected()) {
            sshClient = new SSHClient();
            sshClient.addHostKeyVerifier(new PromiscuousVerifier());
            sshClient.connect(sshSetting.getHostname(), sshSetting.getPort());
            if (sshSetting.getKeyPath() != null) {
                sshClient.authPublickey(sshSetting.getUser(), getKeyProvider());
            } else {
                sshClient.authPassword(sshSetting.getUser(), sshSetting.getPassword());
            }
        }
        return sshClient;
    }

    private KeyProvider getKeyProvider() throws IOException {
        return sshSetting.getKeyPassphrase() != null ?
            sshClient.loadKeys(sshSetting.getKeyPath(), sshSetting.getKeyPassphrase()) :
            sshClient.loadKeys(sshSetting.getKeyPath());
    }

}
