package matchdog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.Buffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by marekful on 01/02/2016.
 */
public class GnubgCommand implements Runnable {

    MatchDog server;
    BufferedReader input;
    PrintWriter output;
    BufferedDebugPrinter printer;
    String command;
    boolean isEvalcmd;

    GnubgCommand(MatchDog server,
                 BufferedReader input,
                 PrintWriter output,
                 BufferedDebugPrinter printer,
                 String command,
                 boolean isEvalcmd)
    {
        this.server = server;
        this.input = input;
        this.output = output;
        this.printer = printer;
        this.command = command;
        this.isEvalcmd = isEvalcmd;
    }

    private void sendCommand() {
        String msg = "sending " + (isEvalcmd ? "EVALUATION CMD" : "BOARD STATE") + "... ";
        long sockettime = System.nanoTime();
        output.printf("%s", command + "\r\n");
        msg += "(" + ((System.nanoTime() - sockettime) / 1000000.0) + " ms) OK, waiting for reply";
        printer.printDebugln(msg);
    }

    @Override
    public void run() {

        String rawReply = "", fibsCommand, unit = "";
        long replytime;
        double replydiff = 0;

        sendCommand();

        for(int i = 0; i < 2; i++) {
            try {
                replytime = System.nanoTime();

                rawReply = input.readLine();

                if((replydiff = (System.nanoTime() - replytime) / 1000000000.0) < 0.001) {
                    replydiff *= 1000;
                    unit = " ms";
                } else {
                    unit = " seconds";
                }

                if(rawReply.startsWith("Error:")) {
                    server.printDebug(" --**--> GNUBG ERROR: ");
                    server.printDebug(rawReply);

                    if(rawReply.contains("no command given")) {
                        server.printDebug("--**--> command: " + command);
                        if(!command.equals("")) {
                            server.printDebug("--**--> resending: " + command);
                            sendCommand();
                            continue;
                        }
                    }
                    return;
                }

                boolean m = Pattern.compile("(\\d\\.[\\d]{6}\\s*){6}").matcher(rawReply).matches();

                if(isEvalcmd && m) {
                    break;
                } else if(!isEvalcmd && !m) {
                    break;
                }

            } catch (IOException e) {
                server.systemPrinter.printDebugln("Exception reading from gnubg external: " + e.getMessage());
            }

            if(i == 0) {
                printer.printDebugln(" ** !! ** trying next line for "
                        + (isEvalcmd ? "EVAL" : "BOARD") + " -   cmd: " + command);
                printer.printDebugln(" ** !! ** trying next line for "
                        + (isEvalcmd ? "EVAL" : "BOARD") + " - reply: " + rawReply);
            } else {
                printer.printDebugln(" ** !! ** GIVING UP for "
                        + (isEvalcmd ? "EVAL" : "BOARD") + " -   cmd: " + command);
                printer.printDebugln(" ** !! ** GIVING UP for "
                        + (isEvalcmd ? "EVAL" : "BOARD") + " - reply: " + rawReply);
                return;
            }
        }

        if(isEvalcmd) {
            printer.printDebugln("gnubg EQUITIES (in " + replydiff + unit + "): " + rawReply);
            parseEquities(rawReply);

        } else {

            if(server.fibs.match == null || server.fibs.match.isFinished()) {
                printer.printDebugln("*!* NOT SENDING to FIBS -> match finished");
                return;
            }

            if(rawReply.equals("")) {
                return;
            }

            printer.printDebugln("gnubg says (in " + replydiff + unit + "): " + rawReply);

            fibsCommand = processReply(rawReply);

            server.fibs.sleepFibs(100);
            server.fibsout.println(fibsCommand);
            server.printDebug("sent to fibs: ");
            server.fibs.printFibsCommand(fibsCommand);
        }
    }

    private String processReply(String line) {

        if(isEvalcmd) {
            return line;
        }

        return transformCommand(line);
    }

    private String transformCommand(String in) {

        String out = in.replace("25", "bar").
                replace("/0", "/off").
                replace("/", "-").
                replace("*", "").
                replace("take", "accept").
                replace("drop", "reject");

        if(out.contains("-")) {

            if(server.fibs.match.isShiftmove()) {
                out = shift(out);
            }
            out = "move " + out;
        }
        return out;
    }

    private void parseEquities(String in) {

        // FIXME
        //// this is bug...
        // sometimes a 'not-to-roll' case is not detected
        // and a board: which results in a 'roll' or other
        // non expected reply is sent from gnubg.
        if(in.startsWith("roll") || in.startsWith("double") || in.contains("/") || in.equals("")) {
            printer.printDebugln("*** !! BUG parseEquities: " + in);
            return;
        }

        if(server.fibs.match == null) {
            printer.printDebugln("*!* NOT PARSING EQUITIES -> match == null");
            return;
        }

        String [] split0 = in.split(" ");
        for(int i = 0; i < 6; i++) {
            if(!split0[i].equals("")) {
                server.fibs.match.equities[i] = Double.parseDouble(split0[i]);
            }
        }
    }

    private String shift(String in) {
        server.printDebug("SHIFTING");
        String [] arr0 = in.split(" ");
        String returnStr = "";
        for(int i = 0; i < arr0.length; i++) {
            String [] arr1 = arr0[i].split("-");

            try {
                int n = Integer.parseInt(arr1[0]);

                if(n < 25 && n > 0) {
                    n = 25 - n;
                    arr1[0] = Integer.toString(n);
                }

            } catch (Exception e) {
                //server.printDebug("not shifting " + arr1[0]);
            }
            try {
                int m = Integer.parseInt(arr1[1]);

                if(m < 25 && m > 0) {
                    m = 25 - m;
                    arr1[1] = Integer.toString(m);
                }

            } catch (Exception e) {
                //server.printDebug("not shifting " + arr1[1]);
            }
            arr0[i] = arr1[0] + "-" + arr1[1];
            returnStr += arr0[i] + " ";
        }
        //server.printDebug("shift result: " + returnStr);
        return returnStr;
    }
}