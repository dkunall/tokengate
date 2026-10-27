package com.tokengate.gateway.domain.pii;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Multi-pattern matcher for the tenant's custom deny list (project code names, customer names). One pass over
 * the text finds every occurrence of every term, O(text + matches) after an O(total term length) build.
 * Matching is case-insensitive, char by char, so match offsets line up with the original text.
 */
public final class AhoCorasick {

    public record Hit(int start, int end, String term) {
    }

    private final List<Map<Character, Integer>> next = new ArrayList<>();
    private final List<Integer> fail = new ArrayList<>();
    private final List<List<String>> out = new ArrayList<>();

    public AhoCorasick(Iterable<String> terms) {
        newNode();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            int node = 0;
            for (int i = 0; i < term.length(); i++) {
                char c = Character.toLowerCase(term.charAt(i));
                Integer child = next.get(node).get(c);
                if (child == null) {
                    child = newNode();
                    next.get(node).put(c, child);
                }
                node = child;
            }
            out.get(node).add(term);
        }
        buildFailureLinks();
    }

    public boolean isEmpty() {
        return next.getFirst().isEmpty();
    }

    public List<Hit> findAll(String text) {
        List<Hit> hits = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            while (node != 0 && !next.get(node).containsKey(c)) {
                node = fail.get(node);
            }
            node = next.get(node).getOrDefault(c, 0);
            for (String term : out.get(node)) {
                hits.add(new Hit(i - term.length() + 1, i + 1, term));
            }
        }
        return hits;
    }

    private int newNode() {
        next.add(new HashMap<>());
        fail.add(0);
        out.add(new ArrayList<>());
        return next.size() - 1;
    }

    private void buildFailureLinks() {
        Queue<Integer> queue = new ArrayDeque<>();
        for (int child : next.getFirst().values()) {
            fail.set(child, 0);
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Map.Entry<Character, Integer> e : next.get(node).entrySet()) {
                char c = e.getKey();
                int child = e.getValue();
                int f = fail.get(node);
                while (f != 0 && !next.get(f).containsKey(c)) {
                    f = fail.get(f);
                }
                int target = next.get(f).getOrDefault(c, 0);
                fail.set(child, target == child ? 0 : target);
                out.get(child).addAll(out.get(fail.get(child)));
                queue.add(child);
            }
        }
    }
}
