package cn.ruc.mblank.core.word2Vec.domain;


import java.io.Serializable;

public class WordEntry implements Comparable<WordEntry>,Serializable{
    public String name;
    public float score;

    public WordEntry(String name, float score) {
        this.name = name;
        this.score = score;
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return this.name + "\t" + score;
    }

    @Override
    public int compareTo(WordEntry o) {
        // TODO Auto-generated method stub
        if (this.score < o.score) {
            return 1;
        } else {
            return -1;
        }
    }

}