package com.example.svgeditor.core

/** Shared fixtures for the `core` module (used by both main and test sources). */
object Samples {
    /** A simple, text-free SVG (so it renders without a font database). */
    const val SIMPLE: String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
          <rect id="bg" x="0" y="0" width="200" height="120" fill="#fafafa"/>
          <rect id="box-a" x="10" y="10" width="80" height="60" fill="#4caf50"/>
          <circle id="dot" cx="150" cy="60" r="30" fill="#e91e63"/>
          <g id="grp" transform="translate(120,80)">
            <rect id="inner" x="0" y="0" width="40" height="20" fill="#2196f3"/>
          </g>
        </svg>
    """

    /** A hand-written layout JSON in the exact shape produced by `resvg_bridge`. */
    const val LAYOUT_JSON: String = """
        {"width":200.0,"height":120.0,"elements":[
          {"index":0,"id":"bg","kind":"path","x":0.0,"y":0.0,"width":200.0,"height":120.0,"transform":[1,0,0,1,0,0]},
          {"index":1,"id":"box-a","kind":"path","x":10.0,"y":10.0,"width":80.0,"height":60.0,"transform":[1,0,0,1,0,0]},
          {"index":2,"id":"dot","kind":"path","x":120.0,"y":30.0,"width":60.0,"height":60.0,"transform":[1,0,0,1,0,0]},
          {"index":3,"id":"grp","kind":"group","x":120.0,"y":80.0,"width":40.0,"height":20.0,"transform":[1,0,0,1,120,80]},
          {"index":4,"id":"inner","kind":"path","x":120.0,"y":80.0,"width":40.0,"height":20.0,"transform":[1,0,0,1,120,80]}
        ]}
    """

    /**
     * An SVG whose elements carry non-identity transforms — exercises transform-aware move
     * editing. `rot` has its own `rotate(35 50 40)`; `scaled` lives inside a `scale(1.5)
     * translate(10,10)` group. A plain `translate(dx,dy)` prepend would move these in local
     * space and drift from the root-space drag preview.
     */
    const val TRANSFORMED: String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
          <rect id="bg" x="0" y="0" width="200" height="120" fill="#fafafa"/>
          <rect id="rot" x="20" y="20" width="60" height="40" fill="#4caf50" transform="rotate(35 50 40)"/>
          <g id="sgrp" transform="scale(1.5) translate(60,10)">
            <rect id="scaled" x="0" y="0" width="40" height="20" fill="#2196f3"/>
          </g>
        </svg>
    """
}
