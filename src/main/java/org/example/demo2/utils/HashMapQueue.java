package org.example.demo2.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同时含有hashmap 和queue的特性
 * 存储指定容量的数据，便于查找修改
 */
public class HashMapQueue<K, V> {

    /**
     * 队列容量
     */
    int capacity;

    private Object mLock;

    private ConcurrentHashMap<K, V> map;
    private Node[] nodes;

    int objIndex = 0;

    public HashMapQueue(int capacity) {
        this.capacity = capacity;
        map = new ConcurrentHashMap<K, V>((int) (capacity * 1.5));
        nodes = new Node[capacity];
        mLock = new Object();
    }

    public boolean add(K key, V value) {
        if (map.containsKey(key)) {
            return false;
        }

        synchronized (mLock) {
            if (map.contains(key)) {
                return false;
            }

            if (nodes[objIndex] != null) {
                map.remove(nodes[objIndex].key);
                nodes[objIndex] = null;
            }

            Node node = new Node();
            node.key = key;
            node.value = value;
            nodes[objIndex] = node;
            map.put(key,value);

            objIndex = (objIndex + 1) % capacity;
        }
        return true;
    }

    public boolean containsKey(K key) {
        if (map.containsKey(key)) {
            return true;
        }

        return false;
    }

    public V get(K key) {
        return map.get(key);
    }


    public Set<K> keys() {
        return map.keySet();
    }

    public int size() {
        return map.size();
    }

    private class Node<K, V> {
        private K key;
        private V value;
        private int index;

    }
}
