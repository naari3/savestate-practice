package net.naari3.savestate.detcheck;

import java.util.Random;

/** Entity.random (protected) を読むためのインターフェイス。EntityRngMixin が実装する。 */
public interface EntityRandomAccess {
	Random savestate$random();
}
