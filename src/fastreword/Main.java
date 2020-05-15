package fastreword;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevCommitList;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Main {
    private static Repository repo;

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

    private static final Map<RevCommit, ObjectId> tree = new HashMap<>();

    private static ObjectId renameCommit(RevCommit cur, ObjectId target, String message) throws IOException {
        if (tree.containsKey(cur)) {
            return tree.get(cur);
        }



        if (cur.equals(target)) {
            ObjectInserter inserter = repo.getObjectDatabase().newInserter();
            CommitBuilder cb = new CommitBuilder();

            cb.setAuthor(cur.getAuthorIdent());
            cb.setCommitter(cur.getCommitterIdent());
            cb.setMessage(message);
            cb.setParentIds(cur.getParents());
            cb.setTreeId(cur.getTree());

            ObjectId ret = inserter.insert(cb);

            tree.put(cur, ret);

            return ret;
        }

        RevCommit[] parents = cur.getParents();
        ObjectId[] newParents = new ObjectId[parents.length];
        for (int i = 0; i < parents.length; i++) {
            newParents[i] = renameCommit(parents[i], target, message);
        }

        ObjectInserter inserter = repo.getObjectDatabase().newInserter();
        CommitBuilder cb = new CommitBuilder();

        cb.setAuthor(cur.getAuthorIdent());
        cb.setCommitter(cur.getCommitterIdent());
        cb.setMessage(cur.getFullMessage());
        cb.setParentIds(newParents);
        cb.setTreeId(cur.getTree());

        ObjectId ret = inserter.insert(cb);

        tree.put(cur, ret);

        return ret;
    }

    /*private static void buildTree(RevCommit commit) {
        if (visited.contains(commit) || commit.equals(renameObject)) {
            return;
        }

        RevCommit[] parents = commit.getParents();
        if (parents == null) {
            System.out.println("Ooopsie");
            return;
        }
        for (RevCommit parent : parents) {
            Node parentNode;

            if (tree.containsKey(parent)) {
                parentNode = tree.get(parent);
            } else {
                parentNode = new Node(parent);
                tree.put(parent, parentNode);
            }

            parentNode.addChild(commit);
            buildTree(parent);
        }

        visited.add(commit);
    }*/

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: <reference> <message>");
            return;
        }
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            repo = builder.setGitDir(new File("/home/rbblly/android/google-4.9/private/msm-google/.git"))
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();


            ObjectId headObject = repo.resolve("HEAD");
            ObjectId renameObject = repo.resolve(args[0]);
            //RevWalk rew = new RevWalk(repo);
            //RevCommit headCommit = rew.parseCommit(headObject);
            //buildTree(headCommit);

            System.out.println("Done !");

            RevWalk revWalk = new PlotWalk(repo);
            ObjectId rootId = headObject;
            RevCommit root = revWalk.parseCommit(rootId);
            revWalk.markStart(root);
            RevCommitList<RevCommit> commitList = new RevCommitList<>();
            commitList.source(revWalk);
            commitList.fillTo(Integer.MAX_VALUE);

            ObjectId result = renameCommit(commitList.get(0), renameObject, args[1]);

            //PlotCommit<P> rename = (PlotCommit) revWalk.lookupCommit(renameObject);

            /* commitList.
            PlotCommit<PlotLane> headCommit = commitList.get(0);
            renameCommit(headCommit, renameObject);*/

            System.out.println(result);


        } catch (IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }
}
