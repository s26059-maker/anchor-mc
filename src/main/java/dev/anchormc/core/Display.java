package dev.anchormc.core;

import java.util.List;
import java.util.UUID;

/** 클라이언트로 나가는 유일한 창구. 서버 월드는 건드리지 않는다. DecoyEngine 밖에서는 호출하지 않는다. */
public interface Display {
    /** 뭉치의 모든 블록을 한 번에 보낸다(구현은 섹션당 패킷 하나로 묶을 수 있다). */
    void show(UUID player, List<Voxel> voxels);

    /** 진짜 블록을 다시 보낸다. */
    void hide(UUID player, List<Pos> positions);
}
