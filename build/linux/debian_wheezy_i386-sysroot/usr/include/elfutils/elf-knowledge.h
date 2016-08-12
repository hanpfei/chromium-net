/* Accumulation of various pieces of knowledge about ELF.
   Copyright (C) 2000, 2001, 2002, 2003, 2005 Red Hat, Inc.
   This file is part of Red Hat elfutils.
   Written by Ulrich Drepper <drepper@redhat.com>, 2000.

   Red Hat elfutils is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by the
   Free Software Foundation; version 2 of the License.

   Red Hat elfutils is distributed in the hope that it will be useful, but
   WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   General Public License for more details.

   You should have received a copy of the GNU General Public License along
   with Red Hat elfutils; if not, write to the Free Software Foundation,
   Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301 USA.

   In addition, as a special exception, Red Hat, Inc. gives You the
   additional right to link the code of Red Hat elfutils with code licensed
   under any Open Source Initiative certified open source license
   (http://www.opensource.org/licenses/index.php) which requires the
   distribution of source code with any binary distribution and to
   distribute linked combinations of the two.  Non-GPL Code permitted under
   this exception must only link to the code of Red Hat elfutils through
   those well defined interfaces identified in the file named EXCEPTION
   found in the source code files (the "Approved Interfaces").  The files
   of Non-GPL Code may instantiate templates or use macros or inline
   functions from the Approved Interfaces without causing the resulting
   work to be covered by the GNU General Public License.  Only Red Hat,
   Inc. may make changes or additions to the list of Approved Interfaces.
   Red Hat's grant of this exception is conditioned upon your not adding
   any new exceptions.  If you wish to add a new Approved Interface or
   exception, please contact Red Hat.  You must obey the GNU General Public
   License in all respects for all of the Red Hat elfutils code and other
   code used in conjunction with Red Hat elfutils except the Non-GPL Code
   covered by this exception.  If you modify this file, you may extend this
   exception to your version of the file, but you are not obligated to do
   so.  If you do not wish to provide this exception without modification,
   you must delete this exception statement from your version and license
   this file solely under the GPL without exception.

   Red Hat elfutils is an included package of the Open Invention Network.
   An included package of the Open Invention Network is a package for which
   Open Invention Network licensees cross-license their patents.  No patent
   license is granted, either expressly or impliedly, by designation as an
   included package.  Should you wish to participate in the Open Invention
   Network licensing program, please visit www.openinventionnetwork.com
   <http://www.openinventionnetwork.com>.  */

#ifndef _ELF_KNOWLEDGE_H
#define _ELF_KNOWLEDGE_H	1

#include <stdbool.h>


/* Test whether a section can be stripped or not.  */
#define SECTION_STRIP_P(shdr, name, remove_comment) \
  /* Sections which are allocated are not removed.  */			      \
  (((shdr)->sh_flags & SHF_ALLOC) == 0					      \
   /* We never remove .note sections.  */				      \
   && (shdr)->sh_type != SHT_NOTE					      \
   && (((shdr)->sh_type) != SHT_PROGBITS				      \
       /* Never remove .gnu.warning.* sections.  */			      \
       || (strncmp (name, ".gnu.warning.", sizeof ".gnu.warning." - 1) != 0   \
	   /* We remove .comment sections only if explicitly told to do so. */\
	   && (remove_comment						      \
	       || strcmp (name, ".comment") != 0)))			      \
   /* So far we do not remove any of the non-standard sections.		      \
      XXX Maybe in future.  */						      \
   && (shdr)->sh_type < SHT_NUM)


/* Test whether `sh_info' field in section header contains a section
   index.  There are two kinds of sections doing this:

   - the sections containing relocation information reference in this
     field the section to which the relocations apply;

   - section with the SHF_INFO_LINK flag set to signal that `sh_info'
     references a section.  This allows correct handling of unknown
     sections.  */
#define SH_INFO_LINK_P(Shdr) \
  ((Shdr)->sh_type == SHT_REL || (Shdr)->sh_type == SHT_RELA		      \
   || ((Shdr)->sh_flags & SHF_INFO_LINK) != 0)


/* When combining ELF section flags we must distinguish two kinds:

   - flags which cause problem if not added to the result even if not
     present in all input sections

   - flags which cause problem if added to the result if not present
     in all input sections

   The following definition is for the general case.  There might be
   machine specific extensions.  */
#define SH_FLAGS_COMBINE(Flags1, Flags2) \
  (((Flags1 | Flags2)							      \
    & (SHF_WRITE | SHF_ALLOC | SHF_EXECINSTR | SHF_LINK_ORDER		      \
       | SHF_OS_NONCONFORMING | SHF_GROUP))				      \
   | (Flags1 & Flags2 & (SHF_MERGE | SHF_STRINGS | SHF_INFO_LINK)))

/* Similar macro: return the bits of the flags which necessarily must
   match if two sections are automatically combined.  Sections still
   can be forcefully combined in which case SH_FLAGS_COMBINE can be
   used to determine the combined flags.  */
#define SH_FLAGS_IMPORTANT(Flags) \
  ((Flags) & ~((GElf_Xword) 0 | SHF_LINK_ORDER | SHF_OS_NONCONFORMING))


/* Size of an entry in the hash table.  The ELF specification says all
   entries are regardless of platform 32-bits in size.  Early 64-bit
   ports (namely Alpha for Linux) got this wrong.  The wording was not
   clear.

   Several years later the ABI for the 64-bit S390s was developed.
   Many things were copied from the IA-64 ABI (which uses the correct
   32-bit entry size) but what do these people do?  They use 64-bit
   entries.  It is really shocking to see what kind of morons are out
   there.  And even worse: they are allowed to design ABIs.  */
#define SH_ENTSIZE_HASH(Ehdr) \
  ((Ehdr)->e_machine == EM_ALPHA					      \
   || ((Ehdr)->e_machine == EM_S390					      \
       && (Ehdr)->e_ident[EI_CLASS] == ELFCLASS64) ? 8 : 4)

#endif	/* elf-knowledge.h */
