These public ABI headers come from libass 0.17.4:

- https://github.com/libass/libass/tree/0.17.4/libass
- `ass.h` Git blob: `3ff15980530961dffa588d32378750bd2b3f8d72`
- `ass_types.h` Git blob: `4d9e0ec7dcbf8168ff3ea31b24a5eb1ffaea22a0`

Their ISC license is retained in `COPYING` and in the app's license assets.
The adapter resolves public functions from the existing `libmpv.so`; it does not bundle a second libass implementation.
