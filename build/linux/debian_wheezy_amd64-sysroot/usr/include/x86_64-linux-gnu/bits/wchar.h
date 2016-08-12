/* wchar_t type related definitions.
   Copyright (C) 2000 Free Software Foundation, Inc.
   This file is part of the GNU C Library.

   The GNU C Library is free software; you can redistribute it and/or
   modify it under the terms of the GNU Lesser General Public
   License as published by the Free Software Foundation; either
   version 2.1 of the License, or (at your option) any later version.

   The GNU C Library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Lesser General Public License for more details.

   You should have received a copy of the GNU Lesser General Public
   License along with the GNU C Library; if not, write to the Free
   Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
   02111-1307 USA.  */

#ifndef _BITS_WCHAR_H
#define _BITS_WCHAR_H	1

/* Use GCC's __WCHAR_MAX__ when available.  */
#ifdef __WCHAR_MAX__
#define __WCHAR_MAX	__WCHAR_MAX__
#else
#define __WCHAR_MAX	(2147483647)
#endif

/* GCC may also define __WCHAR_UNSIGNED__.
   Use L'\0' to give the expression the correct (unsigned) type.  */
#ifdef __WCHAR_UNSIGNED__
#define __WCHAR_MIN       L'\0'

/* Failing that, rely on the preprocessor's knowledge of the
   signedness of wchar_t.  */
#elif L'\0' - 1 > 0
#define __WCHAR_MIN       L'\0'
#else
#define __WCHAR_MIN       (-__WCHAR_MAX - 1)
#endif

#endif	/* bits/wchar.h */
