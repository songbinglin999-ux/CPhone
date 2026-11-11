package com.yuanhou.hand.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 偏好、应用经验和技能提纲的本机文件。调用方传入应用私有目录，不走云。 */
public final class Playbook {
    private final File dir;
    private final AgentMemory memory;
    private final SkillBook skills;

    private Playbook(File dir, AgentMemory memory, SkillBook skills) {
        this.dir = dir;
        this.memory = memory;
        this.skills = skills;
    }

    public static Playbook open(File dir) {
        try {
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
        } catch (Exception ignored) {
        }
        return new Playbook(dir, AgentMemory.load(dir), SkillBook.load(dir));
    }

    public AgentMemory memory() { return memory; }

    public SkillBook skills() { return skills; }

    public String relevant(String intent, String packageName) {
        String mem = memory.forIntent(intent, packageName);
        String sk = skills.forIntent(intent);
        if (mem.isEmpty()) return sk;
        if (sk.isEmpty()) return mem;
        return mem + sk;
    }

    /**
     * 任务结束时写回。核对通过才记技能和做成的路线；明确失败只记失败经验；取消不记路线。
     */
    public void record(String utterance, StepHistory hist, boolean verifiedDone, String failLabel) {
        memory.learnUtterance(utterance);
        List<StepHistory.Turn> turns = hist == null ? null : hist.turns();
        if (verifiedDone) {
            memory.learnSuccess(turns);
            skills.recordVerified(utterance, turns);
        } else if (failLabel != null && !failLabel.isEmpty()) {
            memory.learnFailure(failLabel, turns);
        }
        memory.save(dir);
        skills.save(dir);
    }
}

final class PlaybookFiles {
    private PlaybookFiles() {}

    static String read(File dir, String name) {
        if (dir == null) return null;
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File dir, String name, String json) throws IOException {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File dest = new File(dir, name);
        File tmp = new File(dir, name + ".tmp");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
