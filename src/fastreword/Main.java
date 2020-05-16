package fastreword;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Main {
    private static Repository repo;
    private static RevCommit headCommit;
    private static RevCommit renameCommit;
    private static RevWalk walk;

    /*
     * Generates topologically sorted list of dependant commits to be regenerated after commit rename
     *
     * Loosely based on JGit's calculatePickList() function
     * We do not need transactionality as JGit, so file operations are replaced with Set access
     *
     * Repo:
     * Source: /org.eclipse.jgit/src/org/eclipse/jgit/api/RebaseCommand.java
     * Revision: git 55b0203c319e5a4375ee36cedd8e1691e2588ff4
     */
    private static List<RevCommit> calculatePickList() throws IOException, GitAPIException {
        Iterable<RevCommit> commitsToUse;
        try (Git git = new Git(repo)) {
            LogCommand cmd = git.log().addRange(renameCommit, headCommit);
            commitsToUse = cmd.call();
        }
        List<RevCommit> cherryPickList = new ArrayList<>();
        for (RevCommit commit : commitsToUse) {
            cherryPickList.add(commit);
        }
        Collections.reverse(cherryPickList);

        walk.reset();
        walk.setRevFilter(RevFilter.MERGE_BASE);
        walk.markStart(renameCommit);
        walk.markStart(headCommit);
        RevCommit base;

        Set<RevCommit> handled = new HashSet<>();

        while ((base = walk.next()) != null) {
            handled.add(base);
        }

        Iterator<RevCommit> iterator = cherryPickList.iterator();
        pickLoop: while(iterator.hasNext()) {
            RevCommit commit = iterator.next();
            for (int i = 0; i < commit.getParentCount(); i++) {
                boolean parentRewritten = handled.contains(commit.getParent(i));
                if (parentRewritten) {
                    handled.add(commit);
                    continue pickLoop;
                }
            }
            // commit is only merged in, needs not be rewritten
            iterator.remove();
        }

        return cherryPickList;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: <reference> <message>");
            return;
        }
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            repo = builder.setGitDir(new File("/home/rbblly/JETBRAINS/git-fast-reword/test-repo/.git"))
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();

            ObjectId headObject = repo.resolve("HEAD");
            ObjectId renameObject = repo.resolve(args[0]);
            walk = new RevWalk(repo);
            headCommit = walk.parseCommit(headObject);
            renameCommit = walk.parseCommit(renameObject);

            List<RevCommit> pickList = calculatePickList();

            Map<AnyObjectId, ObjectId> oldToNew = new HashMap<>(pickList.size() + 1);

            ObjectInserter inserter = repo.getObjectDatabase().newInserter();
            CommitBuilder cb = new CommitBuilder();

            cb.setAuthor(renameCommit.getAuthorIdent());
            cb.setCommitter(renameCommit.getCommitterIdent());
            cb.setMessage(args[1]);
            cb.setParentIds(renameCommit.getParents());
            cb.setTreeId(renameCommit.getTree());

            oldToNew.put(renameCommit, inserter.insert(cb));

            for (RevCommit cmt : pickList) {
                RevCommit[] oldParents = cmt.getParents();
                List<AnyObjectId> parents = new ArrayList<>(oldParents.length);
                for (RevCommit cur : oldParents) {
                    parents.add(oldToNew.getOrDefault(cur, cur));
                }

                cb.setAuthor(cmt.getAuthorIdent());
                cb.setCommitter(cmt.getCommitterIdent());
                cb.setMessage(cmt.getFullMessage());
                cb.setParentIds(parents);
                cb.setTreeId(cmt.getTree());

                oldToNew.put(cmt, inserter.insert(cb));
            }


            try (Git git = new Git(repo)) {
                ObjectId newHead = oldToNew.get(pickList.get(pickList.size() - 1));
                System.out.println(newHead.getName());
                ResetCommand cmd = git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(newHead.getName());
                cmd.call();
            }

            System.out.println("Done !");
        } catch (IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
    }
}
