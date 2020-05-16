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

import java.io.IOException;
import java.util.*;

public class FastReword {
    /*
     * Produces topologically sorted list of dependant commits to be regenerated after commit rename
     *
     * Loosely based on JGit's calculatePickList() function
     * We do not need transactionality as JGit does, so file operations are replaced with Set access
     *
     * Repo:
     * Source: /org.eclipse.jgit/src/org/eclipse/jgit/api/RebaseCommand.java
     * Revision: git 55b0203c319e5a4375ee36cedd8e1691e2588ff4
     */
    private static List<RevCommit> calculatePickList(Repository repo, RevWalk walk,
                                                     RevCommit headCommit, RevCommit renameCommit)
                                                        throws IOException, GitAPIException {
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

        Set<RevCommit> visited = new HashSet<>();

        while ((base = walk.next()) != null) {
            visited.add(base);
        }

        Iterator<RevCommit> iterator = cherryPickList.iterator();
        pickLoop:
        while (iterator.hasNext()) {
            RevCommit commit = iterator.next();
            for (int i = 0; i < commit.getParentCount(); i++) {
                boolean parentRewritten = visited.contains(commit.getParent(i));
                if (parentRewritten) {
                    visited.add(commit);
                    continue pickLoop;
                }
            }
            // commit is only merged in, needs not be rewritten
            iterator.remove();
        }

        return cherryPickList;
    }

    /*
     * Adds commits with recalculated metadata to object db
     */
    private static ObjectId rewind(Repository repo, RevCommit renameCommit,
                                   String newMessage, List<RevCommit> pickList) throws IOException {
        ObjectInserter inserter = repo.getObjectDatabase().newInserter();
        CommitBuilder cb = new CommitBuilder();

        // Fill CommitBuilder with renameCommit metadata
        cb.setAuthor(renameCommit.getAuthorIdent());
        cb.setCommitter(renameCommit.getCommitterIdent());
        cb.setMessage(newMessage);
        cb.setParentIds(renameCommit.getParents());
        cb.setTreeId(renameCommit.getTree());

        ObjectId newHead;

        if (pickList.isEmpty()) { // We are trying to rename HEAD
            newHead = inserter.insert(cb);
        } else {
            // Store new ObjectIDs to avoid setting a stale parent hash
            Map<AnyObjectId, ObjectId> oldToNew = new HashMap<>(pickList.size() + 1);
            // Invalidate original renameCommit and write its replacement to ObjectDB
            oldToNew.put(renameCommit, inserter.insert(cb));

            for (RevCommit cmt : pickList) {
                RevCommit[] oldParents = cmt.getParents();
                List<AnyObjectId> newParents = new ArrayList<>(oldParents.length);

                for (RevCommit cur : oldParents) {
                    newParents.add(oldToNew.getOrDefault(cur, cur));
                }

                cb.setAuthor(cmt.getAuthorIdent());
                cb.setCommitter(cmt.getCommitterIdent());
                cb.setMessage(cmt.getFullMessage());
                cb.setParentIds(newParents);
                cb.setTreeId(cmt.getTree());

                oldToNew.put(cmt, inserter.insert(cb));
            }

            // New HEAD should be updated tail of pickList
            newHead = oldToNew.get(pickList.get(pickList.size() - 1));
        }

        return newHead;
    }

    public static void main(String[] args) throws IOException, GitAPIException {
        if (args.length != 2) {
            System.err.println("Usage: <reference> <message>");
            throw new IllegalArgumentException("Wrong argument count");
        }

        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir();     // discover git repository

        if (builder.getGitDir() == null) {
            System.err.println("fatal: git repository not found");
            throw new IllegalStateException("Repository not found");
        }

        try (Repository repo = builder.build()) {
            RevWalk walk = new RevWalk(repo);

            ObjectId renameObject = repo.resolve(args[0]);

            if (renameObject == null) { // Object not found by reference
                System.err.println("fatal: unknown reference: " + args[0]);
                throw new IllegalArgumentException("Unknown reference");
            }

            RevCommit headCommit = walk.parseCommit(repo.resolve("HEAD"));
            RevCommit renameCommit = walk.parseCommit(renameObject);

            if (!walk.isMergedInto(renameCommit, headCommit)) {
                System.err.println("fatal: target is not merged in HEAD");
                throw new IllegalArgumentException("Target is not merged in HEAD");
            }

            List<RevCommit> pickList = calculatePickList(repo, walk, headCommit, renameCommit);

            ObjectId newHead = rewind(repo, renameCommit, args[1], pickList);

            try (Git git = new Git(repo)) {
                System.out.println("New HEAD: " + newHead.getName());

                ResetCommand cmd = git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(newHead.getName());
                cmd.call();
            }
        }
    }
}
