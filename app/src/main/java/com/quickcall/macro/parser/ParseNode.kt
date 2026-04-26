package com.quickcall.macro.parser

import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityNodeInfo 의 파싱용 미러 트리 노드.
 *
 * 안드로이드 객체와 분리되어 있으므로 단위 테스트에서 자유롭게 합성 가능.
 */
class ParseNode(
    val text: String? = null,
    val bounds: BBox = BBox(0, 0, 0, 0),
    val children: List<ParseNode> = emptyList()
) {
    /** DFS 순회 */
    fun walk(visitor: (node: ParseNode, parent: ParseNode?) -> Unit, parent: ParseNode? = null) {
        visitor(this, parent)
        for (c in children) c.walk(visitor, this)
    }

    /** 평면 리스트(parent 정보 포함) */
    fun flatten(): List<Flat> {
        val out = ArrayList<Flat>()
        walk({ node, parent -> out.add(Flat(node, parent)) })
        return out
    }

    data class Flat(val node: ParseNode, val parent: ParseNode?)

    /** 자식 중 특정 노드의 인덱스 (referential equality). 없으면 -1 */
    fun indexOfChildIdentity(child: ParseNode): Int {
        for (i in children.indices) if (children[i] === child) return i
        return -1
    }

    /** 자기 자신과 모든 후손 노드 순회 (DFS) */
    fun forEachDescendant(visitor: (ParseNode) -> Unit) {
        visitor(this)
        for (c in children) c.forEachDescendant(visitor)
    }

    companion object {
        /** AccessibilityNodeInfo 트리 → ParseNode 트리 변환 (런타임 전용) */
        fun fromAccessibility(root: AccessibilityNodeInfo, maxDepth: Int = 24): ParseNode {
            return convert(root, 0, maxDepth)
        }

        private fun convert(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): ParseNode {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val children = if (depth >= maxDepth) emptyList() else {
                val n = node.childCount
                if (n <= 0) emptyList()
                else ArrayList<ParseNode>(n).also { list ->
                    for (i in 0 until n) {
                        val c = node.getChild(i) ?: continue
                        list.add(convert(c, depth + 1, maxDepth))
                    }
                }
            }
            return ParseNode(
                text = text,
                bounds = BBox(rect.left, rect.top, rect.right, rect.bottom),
                children = children
            )
        }
    }
}
