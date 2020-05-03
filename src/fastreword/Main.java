package fastreword;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Main {

    private static void rebaseBamboo(Repository repo, List<RevCommit> queue, ObjectId newBase) throws IOException {
        ObjectInserter inserter = repo.getObjectDatabase().newInserter();
        CommitBuilder cb = new CommitBuilder();
        for (RevCommit cmt : queue) {
            cb.setAuthor(cmt.getAuthorIdent());
            cb.setCommitter(cmt.getCommitterIdent());
            cb.setMessage(cmt.getFullMessage());
            cb.setParentId(newBase);
            cb.setTreeId(cmt.getTree());
            newBase = inserter.insert(cb);
        }
        System.out.println(newBase);
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: <reference> <message>");
            return;
        }
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            Repository repo = builder.setGitDir(new File("/home/rbblly/JETBRAINS/git-fast-reword/test-repo/.git"))
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();

            ObjectInserter inserter = repo.getObjectDatabase().newInserter();


            ObjectId headObject = repo.resolve("HEAD");
            ObjectId renameObject = repo.resolve(args[0]);
            RevWalk rew = new RevWalk(repo);
            RevCommit renameCommit = rew.parseCommit(renameObject);


            Iterable<RevCommit> commitsToUse;
            try (Git git = new Git(repo)) {
                LogCommand cmd = git.log().addRange(renameObject, headObject);
                commitsToUse = cmd.call();
            }
            List<RevCommit> cherryPickList = new ArrayList<>();
            for (RevCommit commit : commitsToUse) {
                if (commit.getParentCount() != 1) {
                    System.err.println("Cannot reword commits after ");
                    return;
                }
                cherryPickList.add(commit);
            }
            Collections.reverse(cherryPickList);

            CommitBuilder newBase = new CommitBuilder();
            newBase.setAuthor(renameCommit.getAuthorIdent());
            newBase.setCommitter(renameCommit.getCommitterIdent());
            newBase.setMessage(args[1]);
            newBase.setParentIds(renameCommit.getParents());
            newBase.setTreeId(renameCommit.getTree());

            ObjectId newBaseHash = inserter.insert(newBase);

            rebaseBamboo(repo, cherryPickList, newBaseHash);

        } catch (IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        } catch (GitAPIException e) {
            e.printStackTrace();
        }

    }
}
