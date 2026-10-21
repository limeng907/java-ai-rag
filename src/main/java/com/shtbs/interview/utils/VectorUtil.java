package com.shtbs.interview.utils;

public class VectorUtil {
    public static double cosineSimilarity(float[]a,float[]b){
        if(a==null||b==null||a.length==0||a.length!=b.length){
            throw new IllegalArgumentException("向量维度不匹配");
        }

        double dotProduct=0.0;
        double normA=0.0;
        double normB=0.0;

        for(int i=0;i<a.length;i++){
            dotProduct+=a[i]*b[i];

            normA+=a[i]*a[i];

            normB+=b[i]*b[i];
        }

        if(normA==0||normB==0){
            throw new IllegalArgumentException("不能计算零向量的余弦相似度");
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
